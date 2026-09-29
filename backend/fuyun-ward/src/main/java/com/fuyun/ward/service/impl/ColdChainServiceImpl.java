package com.fuyun.ward.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.ward.api.ColdChainAlertArchivedPayload;
import com.fuyun.ward.api.WardErrorCode;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.dto.RegisterColdChainRecordRequest;
import com.fuyun.ward.dto.SaveColdChainArchiveRequest;
import com.fuyun.ward.entity.ColdChainArchiveEntity;
import com.fuyun.ward.entity.ColdChainRecordEntity;
import com.fuyun.ward.enums.ColdChainPurpose;
import com.fuyun.ward.enums.ColdChainRecordType;
import com.fuyun.ward.internal.WardDomainEvent;
import com.fuyun.ward.mapper.ColdChainArchiveMapper;
import com.fuyun.ward.mapper.ColdChainRecordMapper;
import com.fuyun.ward.service.IColdChainService;
import com.fuyun.ward.vo.ColdChainArchiveVO;
import com.fuyun.ward.vo.ColdChainRecordVO;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 冷链合规台账服务实现（FU-M16 冷链域）：档案 CRUD + 三类型记录登记（ALARM_HANDLE 双人核对与
 * 归档事件发布）+ 巡检 overdue 读时惰性判定。
 *
 * <p>错误码：档案不存在 WD-1004（404）；ALARM_HANDLE 缺 alarm_ref/second_operator WD-1005（400，
 * GC12 冻结「缺 alarm_ref 或双人核对缺第二人」原文）。
 *
 * <p>归档事件时机：ALARM_HANDLE 登记成功后在<b>同一写事务</b>内 publishEvent（WardEventPublisher
 * AFTER_COMMIT 出 MQ，宪法 A.4.2-7 事务内禁直发 MQ）；INSPECTION/DEVIATION 无事件。
 *
 * <p>巡检合规基线（brief 冻结）：每日≥2 次、间隔≥6h——查询面读时惰性判定 overdue 标记+注记
 * （不落库），delay 队列档位归 W-27 PR-4。
 *
 * <p>装配归 WardWebConfig @Import（com.fuyun.ward 不在组件扫描范围，宪法 B.1）。
 */
@Slf4j
public class ColdChainServiceImpl implements IColdChainService {

    /** 审计留痕系统操作人（无登录上下文回退值，与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 巡检合规基线：每日最少次数（brief 冻结「每日≥2 次」） */
    private static final int DAILY_INSPECTION_MIN = 2;

    /** 巡检合规基线：两次巡检最大间隔（brief 冻结「间隔≥6h」） */
    private static final Duration INSPECTION_MAX_INTERVAL = Duration.ofHours(6);

    private final ColdChainArchiveMapper archiveMapper;

    private final ColdChainRecordMapper recordMapper;

    private final com.fuyun.ward.cache.WardSeqGate seqGate;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 WardWebConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param archiveMapper 冷链档案 mapper，非空；档案 CRUD 通道
     * @param recordMapper  冷链记录 mapper，非空；记录登记与巡检聚合通道
     * @param seqGate       业务号发号器，非空；建档/登记链签发 archive_no/record_no
     * @param events        Spring 事件发布器，非空；归档事件事务内发布入口
     */
    public ColdChainServiceImpl(
            ColdChainArchiveMapper archiveMapper,
            ColdChainRecordMapper recordMapper,
            com.fuyun.ward.cache.WardSeqGate seqGate,
            ApplicationEventPublisher events) {
        this.archiveMapper = archiveMapper;
        this.recordMapper = recordMapper;
        this.seqGate = seqGate;
        this.events = events;
    }

    /**
     * 冷链档案建档：签发 archive_no（全局唯一，部分唯一索引兜底并发建档窗口）后落行。
     *
     * <p>新建语义边界：当日尚无巡检记录，overdue 注记按合规基线（每日≥2 次）判定恒为 true。
     *
     * @param request 保存请求，非空；来源：POST /cold-chain/archives 请求体（@Valid 后置）——
     *                purpose/deviceId/tempRangeType 必填，verifyDueAt/inventoryDigest 可空
     * @return 建档后视图（overdue=true——新建当日零巡检天然逾期）
     */
    @Override
    @Transactional
    public ColdChainArchiveVO createArchive(SaveColdChainArchiveRequest request) {
        String archiveNo = seqGate.nextArchiveNo();
        ColdChainArchiveEntity entity = new ColdChainArchiveEntity();
        entity.setArchiveNo(archiveNo);
        entity.setPurpose(request.purpose());
        entity.setDeviceId(request.deviceId());
        entity.setTempRangeType(request.tempRangeType());
        entity.setVerifyDueAt(request.verifyDueAt());
        entity.setInventoryDigest(request.inventoryDigest());
        // 数据库写操作：档案落行（archive_no 全局唯一，部分唯一索引兜底并发建档窗口）
        archiveMapper.insert(entity);
        log.info(
                "冷链档案已建档：archiveNo={}，purpose={}，deviceId={}，tempRange={}",
                archiveNo,
                request.purpose(),
                request.deviceId(),
                request.tempRangeType());
        // 新建语义：当日尚无巡检，overdue 按合规基线为 true
        return ColdChainArchiveVO.from(entity, true);
    }

    /**
     * 冷链档案更新（用途/设备/温区/验证到期/存量摘要可维护；archive_no 不可变）。
     *
     * <p>执行流程：存在性前置校验（WD-1004）→ 属性列整行更新 → 按更新后档案惰性重判 overdue。
     *
     * @param archiveNo 档案业务号，非空；来源：PUT 端点路径变量
     * @param request   保存请求，非空；来源：PUT 请求体（@Valid 后置），字段可空性与建档同契约
     * @return 更新后视图（带 overdue 注记）
     * @throws com.fuyun.common.exception.BizException WD-1004（404，档案不存在）
     */
    @Override
    @Transactional
    public ColdChainArchiveVO updateArchive(String archiveNo, SaveColdChainArchiveRequest request) {
        ColdChainArchiveEntity entity = requireArchive(archiveNo);
        entity.setPurpose(request.purpose());
        entity.setDeviceId(request.deviceId());
        entity.setTempRangeType(request.tempRangeType());
        entity.setVerifyDueAt(request.verifyDueAt());
        entity.setInventoryDigest(request.inventoryDigest());
        // 数据库写操作：档案更新（archive_no 不可变，仅维护属性列）
        archiveMapper.updateById(entity);
        log.info("冷链档案已更新：archiveNo={}，operator={}", archiveNo, operator());
        return ColdChainArchiveVO.from(entity, overdueOf(List.of(entity)).getOrDefault(archiveNo, true));
    }

    /**
     * 冷链档案逻辑删（@TableLogic 置 deleted=1）。
     *
     * <p>边界条件：记录不级联删除——台账留痕红线；逻辑删后自然键查询面（requireArchive）不可见。
     *
     * @param archiveNo 档案业务号，非空；来源：DELETE 端点路径变量
     * @throws com.fuyun.common.exception.BizException WD-1004（404，档案不存在）
     */
    @Override
    @Transactional
    public void deleteArchive(String archiveNo) {
        requireArchive(archiveNo);
        // 数据库写操作：逻辑删（@TableLogic 置 deleted=1；记录不级联删除——台账留痕红线）
        archiveMapper.deleteById(requireArchive(archiveNo).getId());
        log.info("冷链档案已逻辑删：archiveNo={}，operator={}", archiveNo, operator());
    }

    /**
     * 冷链档案详情：读时惰性重判巡检 overdue 注记（每日≥2 次、间隔≥6h 两基线取「或」；不落库）。
     *
     * @param archiveNo 档案业务号，非空；来源：GET 详情端点路径变量
     * @return 档案视图（含 overdue 注记；无当日巡检记录时判定 true）
     * @throws com.fuyun.common.exception.BizException WD-1004（404，档案不存在）
     */
    @Override
    @Transactional(readOnly = true)
    public ColdChainArchiveVO getArchive(String archiveNo) {
        ColdChainArchiveEntity entity = requireArchive(archiveNo);
        return ColdChainArchiveVO.from(entity, overdueOf(List.of(entity)).getOrDefault(archiveNo, true));
    }

    /**
     * 冷链档案分页（用途过滤，created_at 倒序）。
     *
     * <p>执行流程：过滤分页查询 → 当页档案 overdue 批量惰性判定（单次 IN 聚合防 N+1，
     * 管理面低频可接受）→ 组装出参。
     *
     * @param purpose 用途过滤，可空=不过滤；来源：查询参数（枚举绑定）
     * @param page    页码（0 基，服务层换算 MP 1 基 current）；来源：查询参数，缺省 0
     * @param size    单页条数（1-200，端点 @Valid 约束）；来源：查询参数，缺省 20
     * @return 分页出参（0 基页码，行内 overdue 注记随行）
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ColdChainArchiveVO> pageArchives(ColdChainPurpose purpose, int page, int size) {
        // 数据库读操作：过滤分页（idx_cold_chain_archive_purpose 准入，0 基请求换算 1 基 current）
        Page<ColdChainArchiveEntity> result = archiveMapper.selectPage(
                new Page<>(page + 1L, size),
                Wrappers.<ColdChainArchiveEntity>lambdaQuery()
                        .eq(purpose != null, ColdChainArchiveEntity::getPurpose, purpose)
                        .orderByDesc(ColdChainArchiveEntity::getCreatedAt));
        // 巡检 overdue 批量判定（单次 IN 聚合查询防 N+1，管理面低频可接受）
        Map<String, Boolean> overdueMap = overdueOf(result.getRecords());
        return PageResult.of(
                result.getRecords().stream()
                        .map(entity ->
                                ColdChainArchiveVO.from(entity, overdueMap.getOrDefault(entity.getArchiveNo(), true)))
                        .toList(),
                page,
                size,
                result.getTotal());
    }

    /**
     * 冷链记录登记（INSPECTION 巡检 / ALARM_HANDLE 告警处置 / DEVIATION 偏差共用入口）。
     *
     * <p>执行流程：档案存在性校验（WD-1004）→ ALARM_HANDLE 必填校验（alarm_ref +
     * second_operator，缺任一 WD-1005，GC12 冻结）→ 签发 record_no 落行（recorded_by 取
     * 操作者上下文）→ ALARM_HANDLE 在同一写事务内发布归档事件（AFTER_COMMIT 出 MQ；
     * 回滚事务不发布）。
     *
     * @param archiveNo 档案业务号，非空；来源：记录登记端点路径变量
     * @param request   登记请求，非空；来源：POST 请求体（@Valid 后置）——recordType 必填；
     *                  alarmRef/secondOperator 仅 ALARM_HANDLE 必填，content 可空
     * @return 登记后记录视图（recordedAt 为登记时刻）
     * @throws com.fuyun.common.exception.BizException WD-1004（404，档案不存在）或
     *                                               WD-1005（400，ALARM_HANDLE 缺必填对——补齐后重试）
     */
    @Override
    @Transactional
    public ColdChainRecordVO registerRecord(String archiveNo, RegisterColdChainRecordRequest request) {
        requireArchive(archiveNo);
        ColdChainRecordType type = request.recordType();
        String alarmRef = request.alarmRef();
        String secondOperator = request.secondOperator();
        if (type == ColdChainRecordType.ALARM_HANDLE
                && (alarmRef == null || alarmRef.isBlank() || secondOperator == null || secondOperator.isBlank())) {
            // GC12 冻结：ALARM_HANDLE 必填 alarm_ref+双人核对第二人（WD-1005 400）
            log.warn("冷链告警处置记录非法（缺 alarm_ref 或双人第二人）：archiveNo={}", archiveNo);
            throw new BizException(
                    WardErrorCode.COLD_CHAIN_RECORD_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "告警处置记录必须携带关联告警号与双人核对第二人：" + archiveNo);
        }
        String recordNo = seqGate.nextRecordNo();
        ColdChainRecordEntity entity = new ColdChainRecordEntity();
        entity.setRecordNo(recordNo);
        entity.setArchiveNo(archiveNo);
        entity.setRecordType(type);
        entity.setAlarmRef(alarmRef);
        entity.setSecondOperator(secondOperator);
        entity.setContent(request.content());
        entity.setRecordedBy(operator());
        entity.setRecordedAt(OffsetDateTime.now());
        // 数据库写操作：记录落行（record_no 全局唯一）
        recordMapper.insert(entity);
        log.info("冷链记录已登记：recordNo={}，archiveNo={}，recordType={}，operator={}", recordNo, archiveNo, type, operator());
        if (type == ColdChainRecordType.ALARM_HANDLE) {
            // 消息发送：处置完成在同一写事务内发布归档事件（AFTER_COMMIT 出 MQ；回滚事务不发布）
            Instant handledAt = Instant.now();
            events.publishEvent(new WardDomainEvent(
                    WardMessagingConstants.EVENT_COLD_CHAIN_ALERT_ARCHIVED,
                    new ColdChainAlertArchivedPayload(
                            archiveNo, recordNo, alarmRef, purposeOf(archiveNo).getCode(), operator(), handledAt),
                    handledAt,
                    MDC.get(WardMessagingConstants.TRACE_ID_MDC_KEY)));
            log.info("冷链告警处置归档事件已发布（事务内）：recordNo={}，alarmRef={}", recordNo, alarmRef);
        }
        return ColdChainRecordVO.from(entity);
    }

    /**
     * 冷链记录列表（档案维度，登记时刻倒序——台账追溯口径）。
     *
     * @param archiveNo 档案业务号，非空；来源：记录列表端点路径变量
     * @return 记录视图清单；档案无记录时为空清单（非 null）
     * @throws com.fuyun.common.exception.BizException WD-1004（404，档案不存在）
     */
    @Override
    @Transactional(readOnly = true)
    public List<ColdChainRecordVO> listRecords(String archiveNo) {
        requireArchive(archiveNo);
        // 数据库读操作：档案维度记录列表（idx_cold_chain_record_archive_type 准入，登记时刻倒序）
        return recordMapper
                .selectList(Wrappers.<ColdChainRecordEntity>lambdaQuery()
                        .eq(ColdChainRecordEntity::getArchiveNo, archiveNo)
                        .orderByDesc(ColdChainRecordEntity::getRecordedAt))
                .stream()
                .map(ColdChainRecordVO::from)
                .toList();
    }

    /**
     * 巡检 overdue 批量读时惰性判定（每日≥2 次、间隔≥6h 两基线取「或」）：
     * 当日 INSPECTION 计数 <2 或最近一次巡检距 now >6h 即逾期（新建档案当日零巡检天然逾期）。
     *
     * @param archives 待判定档案清单（分页行），非空
     * @return 档案号→逾期判定映射（缺省语义 true——无记录即逾期）
     */
    private Map<String, Boolean> overdueOf(List<ColdChainArchiveEntity> archives) {
        if (archives.isEmpty()) {
            return Map.of();
        }
        List<String> archiveNos =
                archives.stream().map(ColdChainArchiveEntity::getArchiveNo).toList();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime startOfDay =
                now.toLocalDate().atStartOfDay(now.getOffset()).toOffsetDateTime();
        // 数据库读操作：当日巡检聚合（archive_no IN + 当日窗口，idx_cold_chain_record_archive_type 准入）
        List<ColdChainRecordEntity> inspections = recordMapper.selectList(Wrappers.<ColdChainRecordEntity>lambdaQuery()
                .in(ColdChainRecordEntity::getArchiveNo, archiveNos)
                .eq(ColdChainRecordEntity::getRecordType, ColdChainRecordType.INSPECTION)
                .ge(ColdChainRecordEntity::getRecordedAt, startOfDay));
        Map<String, List<ColdChainRecordEntity>> byArchive =
                inspections.stream().collect(Collectors.groupingBy(ColdChainRecordEntity::getArchiveNo));
        return archiveNos.stream().collect(Collectors.toMap(Function.identity(), archiveNo -> {
            List<ColdChainRecordEntity> today = byArchive.getOrDefault(archiveNo, List.of());
            // 基线一：每日≥2 次；基线二：最近一次距 now ≤6h——任一不满足即逾期
            if (today.size() < DAILY_INSPECTION_MIN) {
                return true;
            }
            OffsetDateTime last = today.stream()
                    .map(ColdChainRecordEntity::getRecordedAt)
                    .max(OffsetDateTime::compareTo)
                    .orElseThrow();
            return last.isBefore(now.minus(INSPECTION_MAX_INTERVAL));
        }));
    }

    /**
     * 档案存在性校验（WD-1004 404）。
     *
     * @param archiveNo 档案业务号，非空
     * @return 档案实体，非空
     */
    private ColdChainArchiveEntity requireArchive(String archiveNo) {
        // 数据库读操作：自然键 archive_no 单查（@TableLogic 自动携带 deleted=0）
        ColdChainArchiveEntity entity = archiveMapper.selectOne(
                Wrappers.<ColdChainArchiveEntity>lambdaQuery().eq(ColdChainArchiveEntity::getArchiveNo, archiveNo));
        if (entity == null) {
            throw new BizException(WardErrorCode.COLD_CHAIN_NOT_FOUND, HttpStatus.NOT_FOUND, "冷链档案不存在：" + archiveNo);
        }
        return entity;
    }

    /**
     * 归档事件 purpose 值来源：登记请求未携带用途，按归属档案用途回填（payload 契约非空）。
     * 语义注记：requireArchive 已校验档案在位，用途必非空；重复查询为契约忠实回填的最小代价。
     *
     * @param archiveNo 档案业务号，非空
     * @return 归属档案用途，非空
     */
    private ColdChainPurpose purposeOf(String archiveNo) {
        return requireArchive(archiveNo).getPurpose();
    }

    /** 操作者取值（写路径审计留痕；无登录上下文回退 system，与审计列默认同源）。 */
    private static String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
