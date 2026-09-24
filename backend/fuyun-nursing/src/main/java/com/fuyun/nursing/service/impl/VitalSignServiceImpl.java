package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.VitalSignRecordedPayload;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.constants.NursingVitalThresholds;
import com.fuyun.nursing.constants.VitalSignValues;
import com.fuyun.nursing.dto.VitalSignRecordRequest;
import com.fuyun.nursing.dto.VitalSignRejectRequest;
import com.fuyun.nursing.entity.VitalSignRecord;
import com.fuyun.nursing.enums.TempSite;
import com.fuyun.nursing.enums.VitalReviewStatus;
import com.fuyun.nursing.enums.VitalSource;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.VitalSignRecordMapper;
import com.fuyun.nursing.service.INursingRecordService;
import com.fuyun.nursing.service.ITemperatureChartService;
import com.fuyun.nursing.service.IVitalSignService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.AbnormalItemVO;
import com.fuyun.nursing.vo.VitalSignVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生命体征域服务实现（V803 vital_sign_record 业务面）。record 七步链（Task 11 IT 断言口径）：
 * 极限拒收闸门 → 在区校验 → insert（唯一冲突幂等拒绝）→ abnormal 判定 → 观察行归集 →
 * 体温单 VITAL 条目写入 → 事件发布（事务内发布 AFTER_COMMIT 出站，GC8）；confirm/reject 走
 * @Update CAS（GC26）。测量时点/业务时间一律服务器时间（GC25）；patient_id/ward_id 由在区行
 * 服务端装配（不信客户端）；iot_quality/conflict_ref 列 P1 无写入方（GC17-① IoT 降级，P2 填）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class VitalSignServiceImpl extends ServiceImpl<VitalSignRecordMapper, VitalSignRecord>
        implements IVitalSignService {

    /** 系统链路等无登录上下文场景的操作者回退值（与 V803 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    private final IWardMetaService wardMetaService;

    private final INursingRecordService nursingRecordService;

    private final ITemperatureChartService temperatureChartService;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param vitalSignMapper       体征记录 mapper，非空；ServiceImpl 基座 mapper
     * @param wardMetaService       病区元数据服务，非空；在区校验与患者/病区归一（Task 3 面）
     * @param nursingRecordService  护理记录服务，非空；观察行归集落点（Task 4 冻结面）
     * @param temperatureChartService 体温单服务，非空；VITAL 条目写入（Task 4 冻结面）
     * @param events                Spring 应用事件发布器，非空；事务内发布经 NursingEventPublisher
     *                              AFTER_COMMIT 出 MQ（GC8 红线，OutpatientEventPublisher 同款进程内桥）
     */
    public VitalSignServiceImpl(
            VitalSignRecordMapper vitalSignMapper,
            IWardMetaService wardMetaService,
            INursingRecordService nursingRecordService,
            ITemperatureChartService temperatureChartService,
            ApplicationEventPublisher events) {
        this.wardMetaService = wardMetaService;
        this.nursingRecordService = nursingRecordService;
        this.temperatureChartService = temperatureChartService;
        this.events = events;
    }

    /**
     * 体征录入（手工/PDA 点测，录入即 CONFIRMED）七步链：①生理极限拒收闸门（越界 NS-1005，400）
     * → ②在区校验（查无在区行定性 NS-1004 患者不在区）→ ③insert（(visit_id, measured_at,
     * site_key) 唯一冲突转 NS-1016 幂等拒绝，不覆盖首值）→ ④abnormal 判定（NursingVitalThresholds
     * 阈值快照随行落库）→ ⑤观察行归集（全项正常合并 abnormal=false / 任一越界独立落行
     * abnormal=true）→ ⑥体温单 VITAL 条目写入（失败上抛不吞——事务整体回滚）→ ⑦发布
     * nursing.vital-sign.recorded（事务内发布，AFTER_COMMIT 出站，GC8）。测量时点一律服务器
     * 时间（GC25）；source 空缺省 MANUAL，IOT 源 P1 无处理路径显式拒收。
     *
     * @param req 录入入参，非空；来源：操作者工作站表单/PDA 上传
     * @return 体征记录出参，非空
     * @throws BizException NS-1005（400 超生理极限拒收）/ NS-1004（409 患者不在区）/
     *                      NS-1016（409 同刻同部位重复录入，幂等拒绝）/ NS-1019（400 source/tempSite code 非法）
     */
    @Override
    @Transactional
    public VitalSignVO record(VitalSignRecordRequest req) {
        // 守卫链⓪：体温部位/数据源 code 显式格式校验（禁裸值入库，W-22⑦ 先例）
        TempSite tempSite = null;
        if (req.tempSite() != null) {
            tempSite = TempSite.fromCode(req.tempSite());
            if (tempSite == null) {
                throw new BizException(
                        NursingErrorCode.PARAM_FORMAT_INVALID,
                        HttpStatus.BAD_REQUEST,
                        "体温部位 code 非法：" + req.tempSite());
            }
        }
        VitalSource source = req.source() == null ? VitalSource.MANUAL : VitalSource.fromCode(req.source());
        if (source == null || source == VitalSource.IOT) {
            // IOT 源归 P2（周期拉取/质量闸门/冲突仲裁），P1 录入面无处理路径，禁落卡后悬置
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "数据源 code 非法或 P1 不支持（IOT 归 P2）：" + req.source());
        }
        // 守卫链①：生理极限拒收闸门（医疗安全护栏，越界 400 拒收 NS-1005，脏值不入库）
        VitalSignValues values = new VitalSignValues(
                req.temperature(),
                req.tempSite(),
                req.pulse(),
                req.respiration(),
                req.systolicBp(),
                req.diastolicBp(),
                req.spo2(),
                req.painScore());
        NursingVitalThresholds.assertWithinPhysiologicalLimit(values);
        // 守卫链②：在区校验（patient_id/ward_id 由在区行服务端装配，不信客户端）
        WardPatientDetailVO inWard = requireInWard(req.visitId());
        String operator = operator();
        OffsetDateTime measuredAt = OffsetDateTime.now();
        // 守卫链④前置：abnormal 判定（阈值结果快照随行落库，观察行归集与事件载荷共用同一判定）
        List<AbnormalItemVO> abnormals = NursingVitalThresholds.abnormalItems(values);
        boolean abnormal = !abnormals.isEmpty();
        VitalSignRecord row = new VitalSignRecord();
        row.setVisitId(req.visitId());
        row.setPatientId(inWard.patientId());
        row.setWardId(inWard.wardId());
        row.setMeasuredAt(measuredAt);
        row.setTemperature(req.temperature());
        row.setTempSite(tempSite == null ? null : tempSite.getCode());
        row.setPulse(req.pulse());
        row.setRespiration(req.respiration());
        row.setSystolicBp(req.systolicBp());
        row.setDiastolicBp(req.diastolicBp());
        row.setSpo2(req.spo2());
        row.setWeight(req.weight());
        row.setHeight(req.height());
        row.setPainScore(req.painScore());
        row.setSource(source.getCode());
        // 手工/PDA 点测录入即 CONFIRMED（Spec :130），复核人/复核时间随录盖章
        row.setReviewStatus(VitalReviewStatus.CONFIRMED.getCode());
        row.setReviewedBy(operator);
        row.setReviewedAt(measuredAt);
        row.setAbnormalFlag(abnormal);
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：体征记录落库（uk_vital_sign_visit_time_site 部分唯一索引兜底防双写）
            baseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 同 (visit_id, measured_at, 部位) 已有首值：幂等拒绝，不覆盖（Spec :108 防双写红线）
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "同刻同部位体征已存在（幂等拒绝，不覆盖首值）：visitId=" + req.visitId() + "，tempSite=" + req.tempSite() + "，measuredAt="
                            + measuredAt);
        }
        // 步骤⑤：观察行归集——全项正常合并当日观察行（abnormal=false）/任一越界独立落行（true）
        String content = abnormal
                ? abnormals.stream().map(AbnormalItemVO::detail).collect(Collectors.joining("；"))
                : normalSummary(values);
        nursingRecordService.appendObservation(req.visitId(), content, abnormal, operator);
        // 步骤⑥：体温单 VITAL 条目写入（录入即 CONFIRMED 必写；失败上抛不吞，事务整体回滚）
        temperatureChartService.appendVitalEntry(req.visitId(), measuredAt.toInstant(), row.getId(), row.getTempSite());
        // 步骤⑦：转正入卡事件（事务内发布，AFTER_COMMIT 出站 GC8；载荷 abnormal 与归集判定同源）
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_VITAL_SIGN_RECORDED,
                new VitalSignRecordedPayload(
                        row.getPatientId(),
                        row.getVisitId(),
                        measuredAt.toInstant(),
                        row.getSource(),
                        row.getReviewStatus(),
                        abnormal)));
        log.info(
                "体征记录落卡：visitId={}，patientId={}，measuredAt={}，source={}，tempSite={}，abnormal={}，operator={}",
                row.getVisitId(),
                row.getPatientId(),
                measuredAt,
                row.getSource(),
                row.getTempSite(),
                abnormal,
                operator);
        return VitalSignVO.from(row);
    }

    /**
     * 按患者主索引列体征记录（测量时点升序）；from/to 均可空（空=不设边界，窗口含头不含尾，
     * TIMESTAMPTZ 按时刻比较不绑定存储偏移）。
     *
     * @param patientId 患者主索引，非空；来源：查询参数
     * @param from      窗口起点（含），可空；来源：查询参数
     * @param to        窗口终点（不含），可空；来源：查询参数
     * @return 体征出参清单（无行返回空清单，非 null）；按测量时点升序
     */
    @Override
    @Transactional(readOnly = true)
    public List<VitalSignVO> listByPatient(long patientId, Instant from, Instant to) {
        var wrapper = Wrappers.<VitalSignRecord>lambdaQuery().eq(VitalSignRecord::getPatientId, patientId);
        if (from != null) {
            wrapper.ge(VitalSignRecord::getMeasuredAt, OffsetDateTime.ofInstant(from, ZoneId.systemDefault()));
        }
        if (to != null) {
            // 窗口含头不含尾：to 为开区间上界（与护理记录单当日窗口同口径）
            wrapper.lt(VitalSignRecord::getMeasuredAt, OffsetDateTime.ofInstant(to, ZoneId.systemDefault()));
        }
        wrapper.orderByAsc(VitalSignRecord::getMeasuredAt);
        // 数据库读操作：患者体征清单（测量时点升序；逻辑删由 @TableLogic 自动过滤）
        return baseMapper.selectList(wrapper).stream().map(VitalSignVO::from).toList();
    }

    /**
     * 按患者主索引取最近一次体征（ALGO-01 行为保持优化点查）：ORDER BY measured_at DESC,
     * id DESC LIMIT 1 单行点查——与旧「升序清单取末位」同为最近测量时点；id DESC 为同刻 tie
     * 的确定性 tie-break（旧升序无次键，同刻多行取值依赖 DB 返回顺序，本实现收敛取最新落卡行）。
     * 复杂度改善：O(患者终身体征行数) 全量拉取+VO 转换 → O(1) 单行回表（配合 PERF-02
     * idx_vital_sign_patient_time 前导索引逆序扫描首行即止）。
     *
     * @param patientId 患者主索引，非空；来源：PDA 摘要标识解析归一后的主档 id
     * @return 最近一次体征出参；患者无体征记录时返回 null（与旧路径空清单取 null 语义对齐）
     */
    @Override
    @Transactional(readOnly = true)
    public VitalSignVO latestByPatient(long patientId) {
        // 数据库读操作：患者最近一次体征单行点查（排序与截断下推 DB；逻辑删由 @TableLogic 自动过滤）
        VitalSignRecord row = baseMapper.selectOne(Wrappers.<VitalSignRecord>lambdaQuery()
                .eq(VitalSignRecord::getPatientId, patientId)
                .orderByDesc(VitalSignRecord::getMeasuredAt)
                .orderByDesc(VitalSignRecord::getId)
                .last("LIMIT 1"));
        return row == null ? null : VitalSignVO.from(row);
    }

    /**
     * 病区待复核体征清单（复核工作台数据源）：仅 review_status=PENDING_REVIEW 行，按测量时点
     * 升序。P1 待复核行无生产写入方（IoT 归 P2），本端点随复核状态机 P1 可达。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @return 待复核出参清单（无行返回空清单，非 null）；按测量时点升序
     */
    @Override
    @Transactional(readOnly = true)
    public List<VitalSignVO> pendingReview(String wardId) {
        // 数据库读操作：病区待复核行（review_status 谓词滤除已转正/已驳回行；逻辑删自动过滤）
        return baseMapper
                .selectList(Wrappers.<VitalSignRecord>lambdaQuery()
                        .eq(VitalSignRecord::getWardId, wardId)
                        .eq(VitalSignRecord::getReviewStatus, VitalReviewStatus.PENDING_REVIEW.getCode())
                        .orderByAsc(VitalSignRecord::getMeasuredAt))
                .stream()
                .map(VitalSignVO::from)
                .toList();
    }

    /**
     * 复核转正（PENDING_REVIEW→CONFIRMED）：@Update CAS 单语句（GC26 条件更新 + 影响行数判定），
     * 0 行定性 NS-1015（不存在或已转正/已驳回）；命中后回读行数据 → 补写体温单 VITAL 条目
     * （转正入权威栏，Spec :137 流程 4）→ 发布 nursing.vital-sign.recorded（载荷
     * reviewStatus=CONFIRMED，语义=「转正入卡」）。
     *
     * @param id 体征记录 id，非空；来源：路径参数
     * @return 转正后记录出参，非空
     * @throws BizException NS-1015（409 仅待复核行可转正）/ NS-1016（409 体征行回读缺失）/ NS-1016（409 同键条目已存在，幂等拒绝）
     */
    @Override
    @Transactional
    public VitalSignVO confirm(Long id) {
        String operator = operator();
        // 数据库写操作：复核转正 CAS（仅 PENDING_REVIEW 可转正，并发重复复核由行数判定兜底）
        if (baseMapper.casReview(id, VitalReviewStatus.CONFIRMED.getCode(), operator, null) == 0) {
            throw new BizException(
                    NursingErrorCode.VITAL_REVIEW_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "体征不存在或非待复核状态，禁止转正：id=" + id);
        }
        VitalSignRecord row = requireById(id);
        // 转正入权威栏：补写体温单 VITAL 条目（时点=测量时点、vitalRef=体征行 id、部位随行可空）
        temperatureChartService.appendVitalEntry(
                row.getVisitId(), row.getMeasuredAt().toInstant(), row.getId(), row.getTempSite());
        // 转正入卡事件（事务内发布，AFTER_COMMIT 出站 GC8；abnormal 快照随载荷出站）
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_VITAL_SIGN_RECORDED,
                new VitalSignRecordedPayload(
                        row.getPatientId(),
                        row.getVisitId(),
                        row.getMeasuredAt().toInstant(),
                        row.getSource(),
                        VitalReviewStatus.CONFIRMED.getCode(),
                        Boolean.TRUE.equals(row.getAbnormalFlag()))));
        log.info(
                "体征复核转正：id={}，visitId={}，patientId={}，measuredAt={}，reviewedBy={}",
                row.getId(),
                row.getVisitId(),
                row.getPatientId(),
                row.getMeasuredAt(),
                operator);
        return VitalSignVO.from(row);
    }

    /**
     * 复核驳回（PENDING_REVIEW→REJECTED）：@Update CAS 单语句（GC26，0 行 → NS-1015），驳回
     * 原因随 CAS 落 remark 留痕；不写体温单条目、不发事件（驳回行不入权威栏，Spec :130）。
     *
     * @param id  体征记录 id，非空；来源：路径参数
     * @param req 驳回入参（原因强制留痕），非空；来源：复核护士录入
     * @return 驳回后记录出参，非空
     * @throws BizException NS-1015（409 仅待复核行可驳回）/ NS-1016（409 体征行回读缺失）
     */
    @Override
    @Transactional
    public VitalSignVO reject(Long id, VitalSignRejectRequest req) {
        String operator = operator();
        // 数据库写操作：复核驳回 CAS（仅 PENDING_REVIEW 可驳回；原因落 remark 审计留痕）
        if (baseMapper.casReview(id, VitalReviewStatus.REJECTED.getCode(), operator, req.reason()) == 0) {
            throw new BizException(
                    NursingErrorCode.VITAL_REVIEW_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "体征不存在或非待复核状态，禁止驳回：id=" + id);
        }
        VitalSignRecord row = requireById(id);
        log.info(
                "体征复核驳回：id={}，visitId={}，patientId={}，measuredAt={}，reason={}，reviewedBy={}",
                row.getId(),
                row.getVisitId(),
                row.getPatientId(),
                row.getMeasuredAt(),
                req.reason(),
                operator);
        return VitalSignVO.from(row);
    }

    /** 全项正常归集内容（合并分支观察行文本）：已测指标摘要 + 正常范围结论。 */
    private String normalSummary(VitalSignValues v) {
        List<String> parts = new ArrayList<>();
        if (v.temperature() != null) {
            parts.add("体温 " + v.temperature().toPlainString() + "℃");
        }
        if (v.pulse() != null) {
            parts.add("脉搏 " + v.pulse() + " 次/分");
        }
        if (v.respiration() != null) {
            parts.add("呼吸 " + v.respiration() + " 次/分");
        }
        if (v.systolicBp() != null && v.diastolicBp() != null) {
            parts.add("血压 " + v.systolicBp() + "/" + v.diastolicBp() + " mmHg");
        } else {
            if (v.systolicBp() != null) {
                parts.add("收缩压 " + v.systolicBp() + " mmHg");
            }
            if (v.diastolicBp() != null) {
                parts.add("舒张压 " + v.diastolicBp() + " mmHg");
            }
        }
        if (v.spo2() != null) {
            parts.add("血氧 " + v.spo2() + "%");
        }
        if (v.painScore() != null) {
            parts.add("疼痛 NRS " + v.painScore() + " 分");
        }
        return String.join("，", parts) + "，均在正常范围";
    }

    /** 按 id 定位体征记录行（逻辑删由 @TableLogic 自动过滤；未命中定性 NS-1016）。 */
    private VitalSignRecord requireById(Long id) {
        VitalSignRecord row = baseMapper.selectById(id);
        if (row == null) {
            // CAS 与回读间被并发逻辑删的极端窗口：资源已不存在，禁继续出卡/出事件
            throw new BizException(NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "体征记录不存在：id=" + id);
        }
        return row;
    }

    /** 按 visit_id 校验在区（IWardMetaService detail；查无在区行定性 NS-1004 患者不在区）。 */
    private WardPatientDetailVO requireInWard(String visitId) {
        try {
            return wardMetaService.detail(visitId);
        } catch (BizException e) {
            // 在区行不存在（detail 定性 NS-1001）→ 体征域语境转 NS-1004 患者不在区
            if (NursingErrorCode.WARD_PATIENT_NOT_FOUND.equals(e.getErrorCode())) {
                throw new BizException(
                        NursingErrorCode.PATIENT_BLOCKED, HttpStatus.CONFLICT, "患者不在区，禁止体征录入：visitId=" + visitId);
            }
            throw e;
        }
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
