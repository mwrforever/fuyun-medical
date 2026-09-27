package com.fuyun.ward.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.ward.api.WardErrorCode;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.dto.CompleteWardCallRequest;
import com.fuyun.ward.dto.CreateWardCallRequest;
import com.fuyun.ward.dto.WardCallQueryRequest;
import com.fuyun.ward.entity.WardCallEntity;
import com.fuyun.ward.entity.WardCallRoutingRuleEntity;
import com.fuyun.ward.enums.CallStatus;
import com.fuyun.ward.enums.CallType;
import com.fuyun.ward.mapper.WardCallMapper;
import com.fuyun.ward.mapper.WardCallRoutingRuleMapper;
import com.fuyun.ward.service.IWardCallService;
import com.fuyun.ward.vo.WardCallRouteVO;
import com.fuyun.ward.vo.WardCallVO;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 呼叫状态机服务实现（FU-M16-01）：六态合法迁移唯一裁决面 + 动作式升级读时惰性判定 + 同床位
 * 合并取消 + 路由规则解析（时段命中）。
 *
 * <p>状态机全迁移表（brief 冻结，CAS 承载行级原子性——WardCallMapper 六 CAS）：
 * <pre>
 *   CREATED     → ANSWERED（应答）/ TRANSFERRED（转接）/ CANCELLED（取消）
 *   ANSWERED    → IN_PROGRESS（处理）/ COMPLETED（完成）/ TRANSFERRED（转接）
 *   IN_PROGRESS → COMPLETED（完成）
 *   TRANSFERRED → ANSWERED（转接后应答，answer 端点回路）
 *   COMPLETED / CANCELLED 终态无出边
 * </pre>
 * 非法迁移统一 WD-1002（409）；CAS 零行（并发态已迁移）同样定性 WD-1002，防绕过 Java 侧校验。
 *
 * <p>升级=动作式：page/get 读路径对超 {@code escalate_after_secs}（默认 300s，常量承载——
 * ward_call 无规则列）未升级行 CAS 递增 escalation_count（DB 字段防重发，inpatient 会诊逾期
 * 标记同款形态），状态不变仍可应答。
 *
 * <p>装配归 WardWebConfig @Import（com.fuyun.ward 不在组件扫描范围，宪法 B.1）。
 */
@Slf4j
public class WardCallServiceImpl implements IWardCallService {

    /** 审计留痕系统操作人（无登录上下文回退值，与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 时段格式：HHmm-HHmm（V1100 time_range 词表） */
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

    /** 升级时限：默认 300 秒（WardMessagingConstants 冻结值） */
    private static final Duration ESCALATE_AFTER =
            Duration.ofSeconds(WardMessagingConstants.ESCALATE_AFTER_SECS_DEFAULT);

    private final WardCallMapper callMapper;

    private final WardCallRoutingRuleMapper routingRuleMapper;

    private final com.fuyun.ward.cache.WardSeqGate seqGate;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 WardWebConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param callMapper        呼叫行 mapper，非空；查询与 CAS 通道
     * @param routingRuleMapper 路由规则 mapper，非空；规则圈定通道
     * @param seqGate           业务号发号器，非空；创建链签发 call_no
     * @param objectMapper      JSON 转换器，非空；target_chain JSONB 原文解析（全局定制实例）
     */
    public WardCallServiceImpl(
            WardCallMapper callMapper,
            WardCallRoutingRuleMapper routingRuleMapper,
            com.fuyun.ward.cache.WardSeqGate seqGate,
            ObjectMapper objectMapper) {
        this.callMapper = callMapper;
        this.routingRuleMapper = routingRuleMapper;
        this.seqGate = seqGate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public WardCallVO create(CreateWardCallRequest request) {
        // 同床位合并语义（brief 冻结）：新呼叫落行前将同床位全部活跃旧呼叫批量置 CANCELLED
        int merged = callMapper.cancelActiveByBed(request.bedId(), operator());
        if (merged > 0) {
            log.info("同床位活跃旧呼叫已合并取消：bedId={}，wardId={}，merged={}", request.bedId(), request.wardId(), merged);
        }
        String callNo = seqGate.nextCallNo();
        WardCallEntity entity = new WardCallEntity();
        entity.setCallNo(callNo);
        entity.setWardId(request.wardId());
        entity.setBedId(request.bedId());
        entity.setPatientId(request.patientId());
        entity.setDeviceId(request.deviceId());
        entity.setCallType(request.callType());
        entity.setSource(request.source());
        entity.setStatus(CallStatus.CREATED);
        entity.setEscalationCount(0);
        entity.setSourceRef(request.sourceRef());
        // 数据库写操作：呼叫落行（状态初始 CREATED，时间戳列由数据库默认值维护）
        callMapper.insert(entity);
        log.info(
                "呼叫已创建：callNo={}，wardId={}，bedId={}，callType={}，source={}",
                callNo,
                request.wardId(),
                request.bedId(),
                request.callType(),
                request.source());
        return WardCallVO.from(requireCall(callNo));
    }

    @Override
    @Transactional
    public WardCallVO answer(String callNo) {
        requireCall(callNo);
        // 数据库写操作：应答 CAS（CREATED/TRANSFERRED → ANSWERED，转接侧支回路同端点）
        if (callMapper.casAnswer(callNo, operator()) == 0) {
            throw illegalTransition(callNo, "应答");
        }
        log.info("呼叫已应答：callNo={}，operator={}", callNo, operator());
        return WardCallVO.from(requireCall(callNo));
    }

    @Override
    @Transactional
    public WardCallVO progress(String callNo) {
        requireCall(callNo);
        // 数据库写操作：处理 CAS（ANSWERED → IN_PROGRESS，可选中间态）
        if (callMapper.casProgress(callNo, operator()) == 0) {
            throw illegalTransition(callNo, "进入处理中");
        }
        log.info("呼叫进入处理中：callNo={}，operator={}", callNo, operator());
        return WardCallVO.from(requireCall(callNo));
    }

    @Override
    @Transactional
    public WardCallVO complete(String callNo, CompleteWardCallRequest request) {
        requireCall(callNo);
        // result_summary 强制（brief 冻结，WD-1005 借承——词表无呼叫域 400 码位，借承申报见类外契约）
        String summary =
                request.resultSummary() == null || request.resultSummary().isBlank() ? null : request.resultSummary();
        if (summary == null) {
            throw new BizException(
                    WardErrorCode.COLD_CHAIN_RECORD_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "完成必须携带处理结果摘要（COMPLETED 必填 result_summary）：" + callNo);
        }
        // 数据库写操作：完成 CAS（ANSWERED/IN_PROGRESS → COMPLETED）
        if (callMapper.casComplete(callNo, summary, operator()) == 0) {
            throw illegalTransition(callNo, "完成");
        }
        log.info("呼叫已完成：callNo={}，operator={}", callNo, operator());
        return WardCallVO.from(requireCall(callNo));
    }

    @Override
    @Transactional
    public WardCallVO transfer(String callNo) {
        requireCall(callNo);
        // 数据库写操作：转接 CAS（CREATED/ANSWERED → TRANSFERRED；规则驱动转接走 route）
        if (callMapper.casTransfer(callNo, operator()) == 0) {
            throw illegalTransition(callNo, "转接");
        }
        log.info("呼叫已转接：callNo={}，operator={}", callNo, operator());
        return WardCallVO.from(requireCall(callNo));
    }

    @Override
    @Transactional
    public WardCallRouteVO route(String callNo) {
        requireCall(callNo);
        // 数据库写操作：规则驱动转接 CAS（先迁移 TRANSFERRED，规则解析失败整体回滚——转接不生效）
        if (callMapper.casTransfer(callNo, operator()) == 0) {
            throw illegalTransition(callNo, "转接触发");
        }
        WardCallEntity call = requireCall(callNo);
        // 数据库读操作：按病区+类型圈定候选规则（量小无分页，时段命中应用层判定）
        List<WardCallRoutingRuleEntity> candidates =
                routingRuleMapper.selectList(Wrappers.<WardCallRoutingRuleEntity>lambdaQuery()
                        .eq(WardCallRoutingRuleEntity::getWardId, call.getWardId())
                        .eq(WardCallRoutingRuleEntity::getCallType, call.getCallType()));
        WardCallRoutingRuleEntity hit = candidates.stream()
                .filter(rule -> inTimeRange(rule.getTimeRange(), OffsetDateTime.now()))
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("呼叫路由未配置：callNo={}，wardId={}，callType={}", callNo, call.getWardId(), call.getCallType());
                    return new BizException(
                            WardErrorCode.CALL_ROUTING_NOT_CONFIGURED,
                            HttpStatus.CONFLICT,
                            "当前时段无生效路由规则：ward=" + call.getWardId() + "，type=" + call.getCallType());
                });
        List<String> targetChain = parseTargetChain(hit.getTargetChain(), callNo);
        boolean taskConvert = Boolean.TRUE.equals(hit.getTaskConvertFlag());
        if (taskConvert && call.getCallType() == CallType.EMERGENCY) {
            // 任务转换判断（brief 冻结「本 PR 落字段与判断」）：M05 任务创建为 PENDING，PR-3 闭合
            log.info("转接触发任务转换（M05 任务创建 PR-3 闭合）：callNo={}，targetChain={}", callNo, targetChain);
        }
        log.info(
                "呼叫路由解析完成：callNo={}，wardId={}，callType={}，targetChain={}",
                callNo,
                call.getWardId(),
                call.getCallType(),
                targetChain);
        return new WardCallRouteVO(callNo, call.getWardId(), call.getCallType(), targetChain, taskConvert);
    }

    @Override
    @Transactional
    public WardCallVO cancel(String callNo) {
        requireCall(callNo);
        // 数据库写操作：取消 CAS（CREATED/TRANSFERRED → CANCELLED；ANSWERED/IN_PROGRESS 不可人工取消）
        if (callMapper.casCancel(callNo, operator()) == 0) {
            throw illegalTransition(callNo, "取消");
        }
        log.info("呼叫已取消：callNo={}，operator={}", callNo, operator());
        return WardCallVO.from(requireCall(callNo));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<WardCallVO> page(WardCallQueryRequest request) {
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        // 数据库读操作：过滤分页（MP 分页 1 基 current 换算 0 基请求；idx_ward_call_ward_status 准入，
        // @TableLogic 自动携带 deleted=0），created_at 降序稳定输出
        Page<WardCallEntity> result = callMapper.selectPage(
                new Page<>(page + 1L, size),
                Wrappers.<WardCallEntity>lambdaQuery()
                        .eq(request.wardId() != null, WardCallEntity::getWardId, request.wardId())
                        .eq(request.status() != null, WardCallEntity::getStatus, request.status())
                        .orderByDesc(WardCallEntity::getCreatedAt));
        List<WardCallVO> content = result.getRecords().stream()
                .map(this::escalateLazy)
                .map(WardCallVO::from)
                .toList();
        return PageResult.of(content, page, size, result.getTotal());
    }

    @Override
    @Transactional
    public WardCallVO get(String callNo) {
        return WardCallVO.from(escalateLazy(requireCall(callNo)));
    }

    /**
     * 升级动作式读时惰性判定（inpatient 会诊逾期标记同款形态）：超 300s 未升级（escalation_count=0
     * 且 created_at 早于阈值）的活跃行 CAS 递增，DB 字段防重发，状态不变仍可应答。
     *
     * @param entity 读路径命中的呼叫行，非空
     * @return 判定后实体（递增命中时 escalation_count +1 的实态），非空
     */
    private WardCallEntity escalateLazy(WardCallEntity entity) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(ESCALATE_AFTER);
        // 数据库写操作：升级 CAS（escalation_count=0 旧值限定兜底并发双读，仅首个判定方递增）
        if (callMapper.casEscalate(entity.getCallNo(), cutoff, operator()) > 0) {
            entity.setEscalationCount(entity.getEscalationCount() + 1);
            log.info(
                    "呼叫超时升级（读时惰性判定）：callNo={}，escalationCount={}，状态保持 {}",
                    entity.getCallNo(),
                    entity.getEscalationCount(),
                    entity.getStatus());
        }
        return entity;
    }

    /**
     * 时段命中判定（V1100 time_range 词表 HHmm-HHmm，起含终不含；支持跨午夜区间）。
     * 包级静态：时段解析为纯函数，测试直驱跨午夜/非法格式分支（墙钟不可注入）。
     *
     * @param timeRange 时段文本，非空；非法格式按不命中处理（规则配置错误不放行误路由）
     * @param now       判定时点，非空
     * @return true=当前时刻落入时段
     */
    static boolean inTimeRange(String timeRange, OffsetDateTime now) {
        if (timeRange == null || !timeRange.matches("\\d{4}-\\d{4}")) {
            log.warn("路由规则时段格式非法（按不命中处理）：timeRange={}", timeRange);
            return false;
        }
        int nowValue = Integer.parseInt(now.format(HHMM));
        String[] parts = timeRange.split("-");
        int start = Integer.parseInt(parts[0]);
        int end = Integer.parseInt(parts[1]);
        // 起含终不含；start>end 视为跨午夜区间（如 2000-0800 命中 [2000,2400)∪[0000,0800)）
        return start <= end ? nowValue >= start && nowValue < end : nowValue >= start || nowValue < end;
    }

    /**
     * target_chain JSONB 原文解析（JSON 字符串数组契约）。
     *
     * @param targetChain 规则目标链 JSON 文本，非空
     * @param callNo      呼叫业务号（日志锚），非空
     * @return 目标链清单，非空
     * @throws IllegalStateException 解析失败（规则配置脏数据）——按路由未配置语义阻断转接，
     *                               建议处理策略：修正规则后重试
     */
    private List<String> parseTargetChain(String targetChain, String callNo) {
        try {
            return objectMapper.readValue(targetChain, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("路由规则 target_chain 与契约不符（JSON 字符串数组）：" + callNo, e);
        }
    }

    /**
     * 呼叫行存在性校验（WD-1001 404）。
     *
     * @param callNo 呼叫业务号，非空
     * @return 呼叫实体，非空
     */
    private WardCallEntity requireCall(String callNo) {
        // 数据库读操作：自然键 call_no 单查（@TableLogic 自动携带 deleted=0）
        WardCallEntity entity =
                callMapper.selectOne(Wrappers.<WardCallEntity>lambdaQuery().eq(WardCallEntity::getCallNo, callNo));
        if (entity == null) {
            throw new BizException(WardErrorCode.CALL_NOT_FOUND, HttpStatus.NOT_FOUND, "呼叫不存在：" + callNo);
        }
        return entity;
    }

    /**
     * 非法迁移异常构造（WD-1002 409；CAS 零行并发场景同口径）。
     *
     * @param callNo 呼叫业务号，非空
     * @param action 动作名（日志/消息锚），非空
     * @return 业务异常，非空
     */
    private static BizException illegalTransition(String callNo, String action) {
        return new BizException(
                WardErrorCode.CALL_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "呼叫状态不允许该操作（" + action + "）：" + callNo);
    }

    /** 操作者取值（写路径审计留痕；无登录上下文回退 system，与审计列默认同源）。 */
    private static String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
