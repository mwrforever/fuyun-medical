package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.payload.LinkageExecutedPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.LinkageLogQueryRequest;
import com.fuyun.iot.dto.SaveLinkageRuleRequest;
import com.fuyun.iot.entity.IotLinkageLogEntity;
import com.fuyun.iot.entity.IotLinkageRuleEntity;
import com.fuyun.iot.enums.LinkageActionResult;
import com.fuyun.iot.enums.LinkageActionType;
import com.fuyun.iot.enums.LinkageTriggerSource;
import com.fuyun.iot.internal.IotDomainEvent;
import com.fuyun.iot.internal.LinkageExecutor;
import com.fuyun.iot.mapper.IotLinkageLogMapper;
import com.fuyun.iot.mapper.IotLinkageRuleMapper;
import com.fuyun.iot.vo.LinkageLogVO;
import com.fuyun.iot.vo.LinkageRuleVO;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 联动规则服务单测（P2 PR-2 Task 9）：规则 CRUD（软删/404 IOT-1017）、触发条件词表校验
 * （未知键/非对象/空值拒保存 IOT-1018 409——防永不命中规则入库）、联动日志分页与人工重推
 * 幂等语义（仅 FAILED 可重推/CAS 并发兜底/规则删停拒推/重执行+登记同事务）。
 *
 * <p>真实 SQL 行为归 IT 回归；JaCoCo 核心包 com.fuyun.iot.service.impl LINE=1.00 承载测试。
 */
@ExtendWith(MockitoExtension.class)
class LinkageRuleServiceImplTest {

    private static final long RULE_ID = 900011L;

    private static final String LINKAGE_NO = "LG2026092600001";

    private static final String ALARM_NO = "AL2026092600001";

    private static final Instant EXECUTED_AT = Instant.parse("2026-09-26T07:00:00Z");

    @Mock
    private IotLinkageRuleMapper ruleMapper;

    @Mock
    private IotLinkageLogMapper logMapper;

    @Mock
    private LinkageExecutor executor;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<IotLinkageRuleEntity> ruleCaptor;

    @Captor
    private ArgumentCaptor<IotDomainEvent> eventCaptor;

    private LinkageRuleServiceImpl service;

    private ObjectMapper objectMapper;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotLinkageRuleEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotLinkageLogEntity.class);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // 无资源事务管理器（最小实现）：承载人工重推「CAS+事件发布」同事务时序
        TransactionTemplate transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {

            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                // 无资源 begin
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                // 无资源 commit：提交点即 AFTER_COMMIT 同步回调触发点
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                // 无资源 rollback：本测试不涉及回滚路径
            }
        });
        service = new LinkageRuleServiceImpl(ruleMapper, logMapper, executor, events, transactions, objectMapper);
    }

    @Test
    @DisplayName("规则清单：全量输出（id 升序由 SQL 承载，视图逐字段映射）")
    void listReturnsAllRulesAsViews() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule()));

        List<LinkageRuleVO> rules = service.listAll();

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).id()).isEqualTo(RULE_ID);
        assertThat(rules.get(0).triggerCondition()).containsEntry("alarm_type", "INFUSION_SHORTAGE");
        assertThat(rules.get(0).actionType()).isEqualTo(LinkageActionType.NOTIFY);
    }

    @Test
    @DisplayName("规则登记：合法条件落行（条件原文序列化、enabled 缺省补 true）")
    void createPersistsRuleWithSerializedCondition() {
        when(ruleMapper.insert(any(IotLinkageRuleEntity.class))).thenReturn(1);

        LinkageRuleVO vo =
                service.create(request(LinkageActionType.NOTIFY, "{\"alarm_type\":\"INFUSION_SHORTAGE\"}", null));

        verify(ruleMapper).insert(ruleCaptor.capture());
        IotLinkageRuleEntity inserted = ruleCaptor.getValue();
        assertThat(inserted.getTriggerCondition()).contains("INFUSION_SHORTAGE");
        assertThat(inserted.getEnabled()).isTrue();
        assertThat(vo.ruleName()).isEqualTo("输液告急→PDA 强提醒");
    }

    @Test
    @DisplayName("规则登记：未知条件键拒保存（IOT-1018 409，防永不命中规则入库）")
    void createRejectsUnknownConditionKey() {
        SaveLinkageRuleRequest request =
                request(LinkageActionType.NOTIFY, "{\"alarm_type\":\"INFUSION_SHORTAGE\",\"ward\":\"5\"}", null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_STATE_NOT_ALLOWED))
                .hasMessageContaining("未知条件键");
        verify(ruleMapper, never()).insert(any(IotLinkageRuleEntity.class));
    }

    @Test
    @DisplayName("规则登记：条件非 JSON 对象拒保存（IOT-1018 409）")
    void createRejectsNonObjectCondition() {
        SaveLinkageRuleRequest request = request(LinkageActionType.NOTIFY, "\"INFUSION_SHORTAGE\"", null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_STATE_NOT_ALLOWED));
        verify(ruleMapper, never()).insert(any(IotLinkageRuleEntity.class));
    }

    @Test
    @DisplayName("规则登记：条件值为空白文本拒保存（IOT-1018 409，空值等值匹配无业务语义）")
    void createRejectsBlankConditionValue() {
        SaveLinkageRuleRequest request = request(LinkageActionType.NOTIFY, "{\"alarm_type\":\" \"}", null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_STATE_NOT_ALLOWED));
        verify(ruleMapper, never()).insert(any(IotLinkageRuleEntity.class));
    }

    @Test
    @DisplayName("规则更新：既有行字段全量覆写")
    void updateRewritesExistingRule() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule());

        LinkageRuleVO vo = service.update(RULE_ID, request(LinkageActionType.WARD_BROADCAST, "{}", null));

        verify(ruleMapper).updateById(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue().getActionType()).isEqualTo(LinkageActionType.WARD_BROADCAST);
        assertThat(vo.actionType()).isEqualTo(LinkageActionType.WARD_BROADCAST);
    }

    @Test
    @DisplayName("规则更新：规则不存在拒绝（IOT-1017 404）")
    void updateRejectsMissingRule() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.update(RULE_ID, request(LinkageActionType.NOTIFY, "{}", null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_RULE_NOT_FOUND));
        verify(ruleMapper, never()).updateById(any(IotLinkageRuleEntity.class));
    }

    @Test
    @DisplayName("规则软删：既有行逻辑删（历史联动日志 rule_id 留痕不受影响）")
    void deleteSoftDeletesExistingRule() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule());

        service.delete(RULE_ID);

        verify(ruleMapper).deleteById(RULE_ID);
    }

    @Test
    @DisplayName("规则软删：规则不存在拒绝（IOT-1017 404）")
    void deleteRejectsMissingRule() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(RULE_ID))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_RULE_NOT_FOUND));
        verify(ruleMapper, never()).deleteById(RULE_ID);
    }

    @Test
    @DisplayName("联动日志分页：过滤条件与分页出参映射（page 0 基）")
    void pageReturnsPagedLogViews() {
        Page<IotLinkageLogEntity> result = new Page<>(1, 20);
        result.setRecords(List.of(log(LinkageActionResult.FAILED, 3)));
        result.setTotal(1);
        when(logMapper.selectPage(any(), any())).thenReturn(result);

        PageResult<LinkageLogVO> page = service.page(new LinkageLogQueryRequest(
                0, 20, RULE_ID, LinkageTriggerSource.ALARM_TRIGGERED, LinkageActionResult.FAILED));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.page()).isZero();
        assertThat(page.content().get(0).linkageNo()).isEqualTo(LINKAGE_NO);
        assertThat(page.content().get(0).actionResult()).isEqualTo(LinkageActionResult.FAILED);
    }

    @Test
    @DisplayName("人工重推：联动日志不存在拒绝（404 借承 IOT-1017，消息区分日志行场景）")
    void retryRejectsMissingLog() {
        when(logMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.retry(LINKAGE_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_RULE_NOT_FOUND))
                .hasMessageContaining("联动执行日志不存在");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("人工重推：非 FAILED 行拒绝（IOT-1018 409——SUCCESS/PENDING 不在重推面）")
    void retryRejectsNonFailedRow() {
        when(logMapper.selectOne(any())).thenReturn(log(LinkageActionResult.SUCCESS, 0));

        assertThatThrownBy(() -> service.retry(LINKAGE_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_STATE_NOT_ALLOWED))
                .hasMessageContaining("仅 FAILED");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("人工重推：规则已删除拒绝（IOT-1018 409，动作配置不可得）")
    void retryRejectsDeletedRule() {
        when(logMapper.selectOne(any())).thenReturn(log(LinkageActionResult.FAILED, 3));
        when(ruleMapper.selectById(RULE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.retry(LINKAGE_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_STATE_NOT_ALLOWED))
                .hasMessageContaining("已删除");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("人工重推：规则已停用拒绝（IOT-1018 409，禁用规则不产生新执行）")
    void retryRejectsDisabledRule() {
        when(logMapper.selectOne(any())).thenReturn(log(LinkageActionResult.FAILED, 3));
        IotLinkageRuleEntity disabled = rule();
        disabled.setEnabled(false);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(disabled);

        assertThatThrownBy(() -> service.retry(LINKAGE_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_STATE_NOT_ALLOWED))
                .hasMessageContaining("已停用");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("人工重推成功：重执行动作+CAS 登记成功+发布 executed 事件（幂等重推面）")
    void retryExecutesActionAndClaimsCasSuccessfully() {
        IotLinkageLogEntity failed = log(LinkageActionResult.FAILED, 3);
        IotLinkageLogEntity succeeded = log(LinkageActionResult.SUCCESS, 4);
        when(logMapper.selectOne(any())).thenReturn(failed).thenReturn(succeeded);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule());
        when(executor.execute(any(IotLinkageRuleEntity.class), eq(ALARM_NO), eq(LINKAGE_NO)))
                .thenReturn(new LinkageExecutor.ActionExecution(LinkageActionResult.SUCCESS, 1, null, EXECUTED_AT));
        when(logMapper.casRetryResult(LINKAGE_NO, "SUCCESS", null, "system")).thenReturn(1);

        LinkageLogVO vo = service.retry(LINKAGE_NO);

        assertThat(vo.actionResult()).isEqualTo(LinkageActionResult.SUCCESS);
        verify(events).publishEvent(eventCaptor.capture());
        IotDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(IotMessagingConstants.EVENT_LINKAGE_EXECUTED);
        LinkageExecutedPayload payload = (LinkageExecutedPayload) event.payload();
        assertThat(payload.linkageNo()).isEqualTo(LINKAGE_NO);
        assertThat(payload.actionResult()).isEqualTo("SUCCESS");
        assertThat(payload.executedAt()).isEqualTo(EXECUTED_AT);
    }

    @Test
    @DisplayName("人工重推并发兜底：CAS 零行（并发已被承接）拒绝且不发布事件（IOT-1018 409）")
    void retryRejectsWhenConcurrentRetryClaimedFirst() {
        when(logMapper.selectOne(any())).thenReturn(log(LinkageActionResult.FAILED, 3));
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule());
        when(executor.execute(any(IotLinkageRuleEntity.class), anyString(), anyString()))
                .thenReturn(new LinkageExecutor.ActionExecution(LinkageActionResult.SUCCESS, 1, null, EXECUTED_AT));
        when(logMapper.casRetryResult(eq(LINKAGE_NO), eq("SUCCESS"), any(), eq("system")))
                .thenReturn(0);

        assertThatThrownBy(() -> service.retry(LINKAGE_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.LINKAGE_STATE_NOT_ALLOWED))
                .hasMessageContaining("并发");
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("人工重推仍失败：重执行再耗尽落 FAILED（retry_count 续增，可再次人工重推）")
    void retryRecordsFailedOutcomeWhenActionFailsAgain() {
        IotLinkageLogEntity failed = log(LinkageActionResult.FAILED, 3);
        IotLinkageLogEntity failedAgain = log(LinkageActionResult.FAILED, 4);
        when(logMapper.selectOne(any())).thenReturn(failed).thenReturn(failedAgain);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule());
        when(executor.execute(any(IotLinkageRuleEntity.class), anyString(), anyString()))
                .thenReturn(
                        new LinkageExecutor.ActionExecution(LinkageActionResult.FAILED, 3, "STOMP 推送失败", EXECUTED_AT));
        when(logMapper.casRetryResult(LINKAGE_NO, "FAILED", "STOMP 推送失败", "system"))
                .thenReturn(1);

        LinkageLogVO vo = service.retry(LINKAGE_NO);

        assertThat(vo.actionResult()).isEqualTo(LinkageActionResult.FAILED);
        verify(events).publishEvent(eventCaptor.capture());
        LinkageExecutedPayload payload =
                (LinkageExecutedPayload) eventCaptor.getValue().payload();
        assertThat(payload.actionResult()).isEqualTo("FAILED");
    }

    /** 构造启用状态的 NOTIFY 联动规则（预置模板①同形） */
    private static IotLinkageRuleEntity rule() {
        IotLinkageRuleEntity rule = new IotLinkageRuleEntity();
        rule.setId(RULE_ID);
        rule.setRuleName("输液告急→PDA 强提醒");
        rule.setTriggerSource(LinkageTriggerSource.ALARM_TRIGGERED);
        rule.setTriggerCondition("{\"alarm_type\": \"INFUSION_SHORTAGE\"}");
        rule.setActionType(LinkageActionType.NOTIFY);
        rule.setEnabled(true);
        return rule;
    }

    /** 构造联动执行日志行（触发引用固定为告警号） */
    private static IotLinkageLogEntity log(LinkageActionResult result, int retryCount) {
        IotLinkageLogEntity entity = new IotLinkageLogEntity();
        entity.setId(1L);
        entity.setLinkageNo(LINKAGE_NO);
        entity.setRuleId(RULE_ID);
        entity.setTriggerSource(LinkageTriggerSource.ALARM_TRIGGERED);
        entity.setTriggerRef(ALARM_NO);
        entity.setActionType(LinkageActionType.NOTIFY);
        entity.setActionResult(result);
        entity.setRetryCount(retryCount);
        entity.setExecutedAt(OffsetDateTime.ofInstant(EXECUTED_AT, ZoneOffset.UTC));
        return entity;
    }

    /** 构造登记/更新请求（enabled 缺省走服务层补齐面；条件原文解析为 JsonNode 载体） */
    private static SaveLinkageRuleRequest request(LinkageActionType actionType, String conditionJson, Boolean enabled) {
        try {
            return new SaveLinkageRuleRequest(
                    "输液告急→PDA 强提醒",
                    LinkageTriggerSource.ALARM_TRIGGERED,
                    new ObjectMapper().readTree(conditionJson),
                    actionType,
                    null,
                    null,
                    enabled);
        } catch (Exception e) {
            throw new IllegalStateException("测试条件原文解析失败", e);
        }
    }
}
