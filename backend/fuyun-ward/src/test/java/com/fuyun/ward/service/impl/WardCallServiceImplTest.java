package com.fuyun.ward.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.ward.dto.CompleteWardCallRequest;
import com.fuyun.ward.dto.CreateWardCallRequest;
import com.fuyun.ward.dto.WardCallQueryRequest;
import com.fuyun.ward.entity.WardCallEntity;
import com.fuyun.ward.enums.CallSource;
import com.fuyun.ward.enums.CallStatus;
import com.fuyun.ward.enums.CallType;
import com.fuyun.ward.mapper.WardCallMapper;
import com.fuyun.ward.mapper.WardCallRoutingRuleMapper;
import com.fuyun.ward.service.IWardCallService;
import com.fuyun.ward.vo.WardCallRouteVO;
import com.fuyun.ward.vo.WardCallVO;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * 呼叫状态机服务单测（P2 PR-2 Task 12 Step 3，TDD 先红后绿）：六态合法迁移表全量、非法迁移
 * WD-1002、同床位合并取消、升级动作式（读时惰性 + DB 防重发）、result_summary 强制（WD-1005 借承）、
 * 路由解析 WD-1003 与呼叫不存在 WD-1001。
 *
 * <p>构造范式：MockitoExtension + 构造器注入 mock + @BeforeAll TableInfoHelper.initTableInfo
 * （MP 实体相关，AlarmServiceImplTest 同款）。
 */
@ExtendWith(MockitoExtension.class)
class WardCallServiceImplTest {

    /** 呼叫业务号夹具 */
    private static final String CALL_NO = "CALL2026092600001";

    /** 操作人夹具（审计留痕口径） */
    private static final String OPERATOR = "nurse-01";

    @Mock
    private WardCallMapper callMapper;

    @Mock
    private WardCallRoutingRuleMapper routingRuleMapper;

    @Mock
    private com.fuyun.ward.cache.WardSeqGate seqGate;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private IWardCallService service;

    @BeforeAll
    static void initTableInfo() {
        // MP 实体元数据初始化（selectPage wrapper 断言依赖 TableInfo，iot 单测同款）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WardCallEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 操作人上下文注入（审计留痕口径）
        com.fuyun.common.context.OperatorContextHolder.set(OPERATOR);
        service = new WardCallServiceImpl(callMapper, routingRuleMapper, seqGate, objectMapper);
    }

    @AfterEach
    void tearDown() {
        // 请求结束清理操作人上下文（ThreadLocal 防线程复用泄漏）
        com.fuyun.common.context.OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("合法迁移表·应答：CREATED→ANSWERED（casAnswer 命中并回读实态）")
    void answerTransitionsCreatedToAnswered() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.CREATED), callRow(CallStatus.ANSWERED));
        when(callMapper.casAnswer(CALL_NO, OPERATOR)).thenReturn(1);

        WardCallVO vo = service.answer(CALL_NO);

        assertThat(vo.status()).isEqualTo(CallStatus.ANSWERED);
    }

    @Test
    @DisplayName("合法迁移表·转接后回答：TRANSFERRED→ANSWERED（侧支回路经 answer 端点）")
    void answerTransitionsTransferredBackToAnswered() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.TRANSFERRED), callRow(CallStatus.ANSWERED));
        when(callMapper.casAnswer(CALL_NO, OPERATOR)).thenReturn(1);

        WardCallVO vo = service.answer(CALL_NO);

        assertThat(vo.status()).isEqualTo(CallStatus.ANSWERED);
    }

    @Test
    @DisplayName("合法迁移表·处理：ANSWERED→IN_PROGRESS（处理人落库）")
    void progressTransitionsAnsweredToInProgress() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.ANSWERED), callRow(CallStatus.IN_PROGRESS));
        when(callMapper.casProgress(CALL_NO, OPERATOR)).thenReturn(1);

        WardCallVO vo = service.progress(CALL_NO);

        assertThat(vo.status()).isEqualTo(CallStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("合法迁移表·完成两出边：ANSWERED→COMPLETED（IN_PROGRESS 可选跳过）")
    void completeTransitionsAnsweredToCompleted() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.ANSWERED), callRow(CallStatus.COMPLETED));
        when(callMapper.casComplete(eq(CALL_NO), eq("处理完毕"), eq(OPERATOR))).thenReturn(1);

        WardCallVO vo = service.complete(CALL_NO, new CompleteWardCallRequest("处理完毕"));

        assertThat(vo.status()).isEqualTo(CallStatus.COMPLETED);
    }

    @Test
    @DisplayName("合法迁移表·完成两出边：IN_PROGRESS→COMPLETED")
    void completeTransitionsInProgressToCompleted() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.IN_PROGRESS), callRow(CallStatus.COMPLETED));
        when(callMapper.casComplete(eq(CALL_NO), eq("处理完毕"), eq(OPERATOR))).thenReturn(1);

        WardCallVO vo = service.complete(CALL_NO, new CompleteWardCallRequest("处理完毕"));

        assertThat(vo.status()).isEqualTo(CallStatus.COMPLETED);
    }

    @Test
    @DisplayName("合法迁移表·转接两出边：ANSWERED→TRANSFERRED")
    void transferTransitionsAnsweredToTransferred() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.ANSWERED), callRow(CallStatus.TRANSFERRED));
        when(callMapper.casTransfer(CALL_NO, OPERATOR)).thenReturn(1);

        WardCallVO vo = service.transfer(CALL_NO);

        assertThat(vo.status()).isEqualTo(CallStatus.TRANSFERRED);
    }

    @Test
    @DisplayName("合法迁移表·取消两出边：CREATED→CANCELLED")
    void cancelTransitionsCreatedToCancelled() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.CREATED), callRow(CallStatus.CANCELLED));
        when(callMapper.casCancel(CALL_NO, OPERATOR)).thenReturn(1);

        WardCallVO vo = service.cancel(CALL_NO);

        assertThat(vo.status()).isEqualTo(CallStatus.CANCELLED);
    }

    @Test
    @DisplayName("非法迁移：COMPLETED 终态再应答拒绝（WD-1002）")
    void answerOnCompletedTerminalRejected() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.COMPLETED));
        when(callMapper.casAnswer(CALL_NO, OPERATOR)).thenReturn(0);

        assertThatThrownBy(() -> service.answer(CALL_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.CALL_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("非法迁移：ANSWERED 不可人工取消（取消出边仅 CREATED/TRANSFERRED，WD-1002）")
    void cancelOnAnsweredRejected() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.ANSWERED));
        when(callMapper.casCancel(CALL_NO, OPERATOR)).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(CALL_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.CALL_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("非法迁移：IN_PROGRESS 不可转接（转接出边仅 CREATED/ANSWERED，WD-1002）")
    void transferOnInProgressRejected() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.IN_PROGRESS));
        when(callMapper.casTransfer(CALL_NO, OPERATOR)).thenReturn(0);

        assertThatThrownBy(() -> service.transfer(CALL_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.CALL_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("非法迁移边界补链：处理/完成在终态 CAS 零行拒绝（WD-1002）")
    void progressAndCompleteOnTerminalRejected() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.COMPLETED), callRow(CallStatus.TRANSFERRED));
        when(callMapper.casProgress(CALL_NO, OPERATOR)).thenReturn(0);
        when(callMapper.casComplete(eq(CALL_NO), anyString(), eq(OPERATOR))).thenReturn(0);

        assertThatThrownBy(() -> service.progress(CALL_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.CALL_STATE_NOT_ALLOWED));
        assertThatThrownBy(() -> service.complete(CALL_NO, new CompleteWardCallRequest("摘要")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.CALL_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("详情读时惰性升级：超时未升级行详情路径递增后回读实态")
    void getEscalatesOverdueRow() {
        WardCallEntity overdue = callRow(CallStatus.CREATED);
        overdue.setCreatedAt(OffsetDateTime.now().minusHours(1));
        when(callMapper.selectOne(any())).thenReturn(overdue);
        when(callMapper.casEscalate(eq(CALL_NO), any(OffsetDateTime.class), eq(OPERATOR)))
                .thenReturn(1);

        WardCallVO vo = service.get(CALL_NO);

        assertThat(vo.escalationCount()).isEqualTo(1);
        assertThat(vo.status()).isEqualTo(CallStatus.CREATED);
    }

    @Test
    @DisplayName("路由转接触发在终态拒绝：casTransfer 零行即 WD-1002 且不解析规则")
    void routeOnTerminalStateRejected() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.COMPLETED));
        when(callMapper.casTransfer(CALL_NO, OPERATOR)).thenReturn(0);

        assertThatThrownBy(() -> service.route(CALL_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.CALL_STATE_NOT_ALLOWED));
        verify(routingRuleMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("路由规则 target_chain 契约不符：解析失败阻断转接（IllegalState 上抛）")
    void routeRejectsContractViolatingTargetChain() throws Exception {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.CREATED), callRow(CallStatus.TRANSFERRED));
        when(callMapper.casTransfer(CALL_NO, OPERATOR)).thenReturn(1);
        com.fuyun.ward.entity.WardCallRoutingRuleEntity rule = new com.fuyun.ward.entity.WardCallRoutingRuleEntity();
        rule.setWardId(1001L);
        rule.setCallType(CallType.NORMAL);
        rule.setTimeRange("0000-2359");
        rule.setTargetChain("not-json");
        when(routingRuleMapper.selectList(any())).thenReturn(List.of(rule));
        when(objectMapper.readValue(eq("not-json"), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("契约不符") {});

        assertThatThrownBy(() -> service.route(CALL_NO)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("时段命中纯函数：起含终不含、跨午夜区间与非法格式三支")
    void timeRangeMatchingCoversMidnightAndInvalidFormats() {
        java.util.function.Function<String, OffsetDateTime> at = hhmm -> OffsetDateTime.of(
                2026,
                9,
                26,
                Integer.parseInt(hhmm.substring(0, 2)),
                Integer.parseInt(hhmm.substring(2, 4)),
                0,
                0,
                java.time.ZoneOffset.UTC);
        // 起含终不含：0800 命中、0759/2000 不命中
        assertThat(WardCallServiceImpl.inTimeRange("0800-2000", at.apply("0800")))
                .isTrue();
        assertThat(WardCallServiceImpl.inTimeRange("0800-2000", at.apply("0759")))
                .isFalse();
        assertThat(WardCallServiceImpl.inTimeRange("0800-2000", at.apply("2000")))
                .isFalse();
        // 跨午夜区间：2000-0600 在 2300 与 0500 命中、1200 不命中
        assertThat(WardCallServiceImpl.inTimeRange("2000-0600", at.apply("2300")))
                .isTrue();
        assertThat(WardCallServiceImpl.inTimeRange("2000-0600", at.apply("0500")))
                .isTrue();
        assertThat(WardCallServiceImpl.inTimeRange("2000-0600", at.apply("1200")))
                .isFalse();
        // 非法格式按不命中处理（规则配置错误不放行误路由）
        assertThat(WardCallServiceImpl.inTimeRange("abc", at.apply("1200"))).isFalse();
        assertThat(WardCallServiceImpl.inTimeRange(null, at.apply("1200"))).isFalse();
    }

    @Test
    @DisplayName("呼叫不存在：动作与详情路径均拒绝（WD-1001）")
    void missingCallRejectedWithNotFound() {
        when(callMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.answer(CALL_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.CALL_NOT_FOUND));
        assertThatThrownBy(() -> service.get(CALL_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.CALL_NOT_FOUND));
    }

    @Test
    @DisplayName("完成缺 result_summary：借承 WD-1005 拒绝（brief 冻结 COMPLETED 必填）")
    void completeWithoutSummaryRejected() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.ANSWERED));

        assertThatThrownBy(() -> service.complete(CALL_NO, new CompleteWardCallRequest("  ")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.COLD_CHAIN_RECORD_INVALID));
        // 摘要缺失前置拒绝：CAS 不触发（状态机不因入参缺陷迁移）
        verify(callMapper, never()).casComplete(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("创建：同床位活跃旧呼叫合并取消（cancelActiveByBed 先于落行）")
    void createCancelsActiveCallsOnSameBed() {
        CreateWardCallRequest request =
                new CreateWardCallRequest(1001L, 5L, 8L, "dev-bedside-1", CallType.NORMAL, CallSource.NURSE_PAD, null);
        when(seqGate.nextCallNo()).thenReturn(CALL_NO);
        when(callMapper.cancelActiveByBed(5L, OPERATOR)).thenReturn(1);
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.CREATED));

        WardCallVO vo = service.create(request);

        verify(callMapper).cancelActiveByBed(5L, OPERATOR);
        ArgumentCaptor<WardCallEntity> captor = ArgumentCaptor.forClass(WardCallEntity.class);
        verify(callMapper).insert(captor.capture());
        assertThat(captor.getValue().getCallNo()).isEqualTo(CALL_NO);
        assertThat(captor.getValue().getStatus()).isEqualTo(CallStatus.CREATED);
        assertThat(vo.callNo()).isEqualTo(CALL_NO);
    }

    @Test
    @DisplayName("Critical-1 注解锁：page 写事务承载（读路径内嵌升级 CAS，禁 readOnly）")
    void pageDeclaresWriteTransactionForLazyEscalation() throws Exception {
        Transactional transactional = WardCallServiceImpl.class
                .getMethod("page", WardCallQueryRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional)
                .as("page 必须显式声明 @Transactional（升级 CAS 写操作的事务承载前提）")
                .isNotNull();
        assertThat(transactional.readOnly())
                .as("read-only 事务下 PG 拒绝 UPDATE——读路径升级 CAS 将使任何非空页 500")
                .isFalse();
    }

    @Test
    @DisplayName("升级动作式：超 300s 未升级行读时递增（escalation_count+1，状态不变）")
    void pageEscalatesOverdueRowsLazily() {
        WardCallEntity overdue = callRow(CallStatus.CREATED);
        overdue.setCreatedAt(OffsetDateTime.now().minusHours(1));
        when(callMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<WardCallEntity> result = invocation.getArgument(0);
            result.setRecords(List.of(overdue));
            result.setTotal(1);
            return result;
        });
        // 升级 CAS 命中：递增后按 +1 实态出网
        when(callMapper.casEscalate(eq(CALL_NO), any(OffsetDateTime.class), eq(OPERATOR)))
                .thenReturn(1);

        PageResult<WardCallVO> result = service.page(new WardCallQueryRequest(0, 20, 1001L, null));

        verify(callMapper).casEscalate(eq(CALL_NO), any(OffsetDateTime.class), eq(OPERATOR));
        assertThat(result.content().get(0).escalationCount()).isEqualTo(1);
        assertThat(result.content().get(0).status()).isEqualTo(CallStatus.CREATED);
    }

    @Test
    @DisplayName("升级防重发：未超时或已升级行 CAS 零行不重复递增")
    void pageSkipsEscalationWhenNotOverdueOrAlreadyEscalated() {
        WardCallEntity fresh = callRow(CallStatus.ANSWERED);
        when(callMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<WardCallEntity> result = invocation.getArgument(0);
            result.setRecords(List.of(fresh));
            result.setTotal(1);
            return result;
        });
        when(callMapper.casEscalate(eq(CALL_NO), any(OffsetDateTime.class), eq(OPERATOR)))
                .thenReturn(0);

        PageResult<WardCallVO> result = service.page(new WardCallQueryRequest(0, 20, 1001L, CallStatus.ANSWERED));

        assertThat(result.content().get(0).escalationCount()).isZero();
    }

    @Test
    @DisplayName("路由解析：命中规则返回目标链与任务转换开关（时段命中判定）")
    void routeResolvesTargetChainFromRule() throws com.fasterxml.jackson.core.JsonProcessingException {
        WardCallEntity emergency = callRow(CallStatus.CREATED);
        emergency.setCallType(CallType.EMERGENCY);
        WardCallEntity transferred = callRow(CallStatus.TRANSFERRED);
        transferred.setCallType(CallType.EMERGENCY);
        when(callMapper.selectOne(any())).thenReturn(emergency, transferred);
        when(callMapper.casTransfer(CALL_NO, OPERATOR)).thenReturn(1);
        com.fuyun.ward.entity.WardCallRoutingRuleEntity rule = new com.fuyun.ward.entity.WardCallRoutingRuleEntity();
        rule.setWardId(1001L);
        rule.setCallType(CallType.EMERGENCY);
        // 全天时段规则（0000-2359 起含终不含覆盖任意当刻）
        rule.setTimeRange("0000-2359");
        rule.setTargetChain("[\"nurse-station-1\",\"head-nurse\"]");
        rule.setTaskConvertFlag(true);
        when(routingRuleMapper.selectList(any())).thenReturn(List.of(rule));
        // target_chain JSONB 原文解析委托打桩：断言服务以规则原文委托全局 ObjectMapper
        when(objectMapper.readValue(
                        eq(rule.getTargetChain()), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(List.of("nurse-station-1", "head-nurse"));

        WardCallRouteVO vo = service.route(CALL_NO);

        assertThat(vo.targetChain()).containsExactly("nurse-station-1", "head-nurse");
        assertThat(vo.taskConvertFlag()).isTrue();
    }

    @Test
    @DisplayName("路由解析：无生效规则拒绝（WD-1003）且转接不生效")
    void routeWithoutRuleRejected() {
        when(callMapper.selectOne(any())).thenReturn(callRow(CallStatus.CREATED));
        when(callMapper.casTransfer(CALL_NO, OPERATOR)).thenReturn(1);
        when(routingRuleMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.route(CALL_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.fuyun.ward.api.WardErrorCode.CALL_ROUTING_NOT_CONFIGURED));
    }

    /** 呼叫行夹具（普通呼叫，升级计数归零） */
    private static WardCallEntity callRow(CallStatus status) {
        WardCallEntity entity = new WardCallEntity();
        entity.setId(1L);
        entity.setCallNo(CALL_NO);
        entity.setWardId(1001L);
        entity.setBedId(5L);
        entity.setPatientId(8L);
        entity.setDeviceId("dev-bedside-1");
        entity.setCallType(CallType.NORMAL);
        entity.setSource(CallSource.NURSE_PAD);
        entity.setStatus(status);
        entity.setEscalationCount(0);
        entity.setCreatedAt(OffsetDateTime.now());
        return entity;
    }
}
