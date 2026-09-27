package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.api.payload.LinkageExecutedPayload;
import com.fuyun.iot.cache.IotSeqGate;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotLinkageLogEntity;
import com.fuyun.iot.entity.IotLinkageRuleEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.LinkageActionResult;
import com.fuyun.iot.enums.LinkageActionType;
import com.fuyun.iot.enums.LinkageTriggerSource;
import com.fuyun.iot.mapper.IotAlarmMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotLinkageLogMapper;
import com.fuyun.iot.mapper.IotLinkageRuleMapper;
import com.fuyun.iot.service.ITelemetryPushService;
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
 * 联动执行器单测（P2 PR-2 Task 9 Step 2 TDD）：触发条件匹配（三键词表/空对象全命中）、五类动作
 * 分派逐项语义（NOTIFY 推送/M01_NOTIFY 降级/CALL_TRANSFER·WARD_BROADCAST·NURSING_TASK 暂存
 * PENDING）、失败同步快速重试（恢复/耗尽 FAILED）、error_msg 列宽防线、人工重推路径复用既有
 * 联动号、iot.linkage.executed 事件发布（载荷契约逐字段）。
 *
 * <p>真实 SQL 行为归 IT 回归；事务模板以无资源事务管理器最小实现承载（真实事务同步链语义，
 * TelemetryIngestServiceImplTest 同款形态）。
 */
@ExtendWith(MockitoExtension.class)
class LinkageExecutorTest {

    private static final String ALARM_NO = "AL2026092600001";

    private static final String LINKAGE_NO = "LG2026092600001";

    private static final String DEVICE_ID = "it-dev-001";

    private static final String METRIC_CODE = "INFUSION_SHORTAGE";

    private static final long WARD_ID = 5L;

    private static final Instant NOW = Instant.parse("2026-09-26T07:00:00Z");

    /** 预置模板①条件原文（V1010 种子同形：告警类型等值匹配） */
    private static final String CONDITION_INFUSION = "{\"alarm_type\": \"INFUSION_SHORTAGE\"}";

    /** 测试告警触发载荷（与告警引擎发布契约同构） */
    private static final AlarmTriggeredPayload PAYLOAD = new AlarmTriggeredPayload(
            ALARM_NO, DEVICE_ID, 5L, "20260901000001", WARD_ID, "CRITICAL", METRIC_CODE, "80", 77L, NOW);

    @Mock
    private IotLinkageRuleMapper ruleMapper;

    @Mock
    private IotLinkageLogMapper logMapper;

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private IotAlarmMapper alarmMapper;

    @Mock
    private IotSeqGate seqGate;

    @Mock
    private ITelemetryPushService pushService;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<IotLinkageLogEntity> logCaptor;

    @Captor
    private ArgumentCaptor<IotDomainEvent> eventCaptor;

    private LinkageExecutor executor;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotLinkageRuleEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotLinkageLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotAlarmEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotDeviceEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 无资源事务管理器（AbstractPlatformTransactionManager 最小实现）：仅承载真实事务同步链
        // 语义——落行+发布同事务时序（TelemetryIngestServiceImplTest 同款形态）
        TransactionTemplate transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {

            @Override
            protected Object doGetTransaction() {
                // 无资源事务令牌：同步激活由父类统一完成，令牌本身无消费方
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                // 无资源 begin：无需打开任何连接资源
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
        executor = new LinkageExecutor(
                ruleMapper,
                logMapper,
                deviceMapper,
                alarmMapper,
                seqGate,
                pushService,
                events,
                transactions,
                new ObjectMapper());
    }

    @Test
    @DisplayName("触发匹配：告警类型条件命中的启用规则执行 NOTIFY 动作——推送+落行 SUCCESS+发布 executed 事件")
    void matchedAlarmRuleExecutesNotifyAndRecordsSuccess() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(900011L, LinkageActionType.NOTIFY, CONDITION_INFUSION)));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);
        when(alarmMapper.selectOne(any())).thenReturn(alarm());

        executor.onAlarmTriggered(PAYLOAD);

        // NOTIFY 动作：WS 告警主题重复强化推送（带 linkage 标记）
        verify(pushService).pushLinkageNotify(any(IotAlarmEntity.class), eq(LINKAGE_NO));
        // 留痕：首试即成（retryCount=0、无 error_msg）
        verify(logMapper).insert(logCaptor.capture());
        IotLinkageLogEntity inserted = logCaptor.getValue();
        assertThat(inserted.getLinkageNo()).isEqualTo(LINKAGE_NO);
        assertThat(inserted.getRuleId()).isEqualTo(900011L);
        assertThat(inserted.getTriggerSource()).isEqualTo(LinkageTriggerSource.ALARM_TRIGGERED);
        assertThat(inserted.getTriggerRef()).isEqualTo(ALARM_NO);
        assertThat(inserted.getActionType()).isEqualTo(LinkageActionType.NOTIFY);
        assertThat(inserted.getActionResult()).isEqualTo(LinkageActionResult.SUCCESS);
        assertThat(inserted.getRetryCount()).isZero();
        assertThat(inserted.getErrorMsg()).isNull();
        assertThat(inserted.getExecutedAt()).isNotNull();
        // 事件发布：iot.linkage.executed 载荷逐字段与契约一致
        verify(events).publishEvent(eventCaptor.capture());
        IotDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(IotMessagingConstants.EVENT_LINKAGE_EXECUTED);
        LinkageExecutedPayload payload = (LinkageExecutedPayload) event.payload();
        assertThat(payload.linkageNo()).isEqualTo(LINKAGE_NO);
        assertThat(payload.ruleId()).isEqualTo(900011L);
        assertThat(payload.triggerSource()).isEqualTo("ALARM_TRIGGERED");
        assertThat(payload.triggerRef()).isEqualTo(ALARM_NO);
        assertThat(payload.actionType()).isEqualTo("NOTIFY");
        assertThat(payload.actionResult()).isEqualTo("SUCCESS");
        assertThat(payload.executedAt()).isNotNull();
    }

    @Test
    @DisplayName("触发匹配：条件不命中的规则跳过（同源多规则仅命中者执行）")
    void unmatchedRuleIsSkipped() {
        IotLinkageRuleEntity matched = rule(900011L, LinkageActionType.NOTIFY, CONDITION_INFUSION);
        IotLinkageRuleEntity unmatched = rule(900012L, LinkageActionType.NOTIFY, "{\"alarm_type\": \"FALL_DETECTED\"}");
        when(ruleMapper.selectList(any())).thenReturn(List.of(matched, unmatched));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);
        when(alarmMapper.selectOne(any())).thenReturn(alarm());

        executor.onAlarmTriggered(PAYLOAD);

        // 仅命中规则取号并落行：不命中者零触达
        verify(seqGate).nextLinkageNo();
        verify(logMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getRuleId()).isEqualTo(900011L);
    }

    @Test
    @DisplayName("触发匹配：空条件对象=全部命中（无条件规则对任意告警生效）")
    void emptyConditionMatchesAllAlarms() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(900013L, LinkageActionType.NOTIFY, "{}")));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);
        when(alarmMapper.selectOne(any())).thenReturn(alarm());

        executor.onAlarmTriggered(PAYLOAD);

        verify(logMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getActionResult()).isEqualTo(LinkageActionResult.SUCCESS);
    }

    @Test
    @DisplayName("触发匹配：device_type 条件以设备档案类型解析——类型相符命中、缺位或不符不命中")
    void deviceTypeConditionResolvesFromDeviceArchive() {
        IotLinkageRuleEntity deviceRule =
                rule(900014L, LinkageActionType.NOTIFY, "{\"device_type\": \"INFUSION_PUMP\"}");
        when(ruleMapper.selectList(any())).thenReturn(List.of(deviceRule));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);
        when(alarmMapper.selectOne(any())).thenReturn(alarm());
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceType("INFUSION_PUMP");
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);

        // 类型相符：命中执行
        executor.onAlarmTriggered(PAYLOAD);

        // 设备类型不符：同一规则不再命中（零落行零事件）
        IotDeviceEntity other = new IotDeviceEntity();
        other.setDeviceId(DEVICE_ID);
        other.setDeviceType("VITALS_MONITOR");
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(other);

        executor.onAlarmTriggered(PAYLOAD);

        // 仅类型相符的第一次触发落行（第二次零触达）
        verify(logMapper, times(1)).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getActionResult()).isEqualTo(LinkageActionResult.SUCCESS);
        verify(seqGate, times(1)).nextLinkageNo();
        verify(events, times(1)).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("无命中规则：零取号零落行零事件（告警侧零感知）")
    void noMatchedRuleProducesNothing() {
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        executor.onAlarmTriggered(PAYLOAD);

        verifyNoInteractions(seqGate, logMapper, events, pushService);
    }

    @Test
    @DisplayName("动作分派 M01_NOTIFY：M01 通知中心缺位降级为留痕成功（GC17①），不触达推送")
    void m01NotifyDegradesToSuccessWithoutPush() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(900015L, LinkageActionType.M01_NOTIFY, CONDITION_INFUSION)));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);

        executor.onAlarmTriggered(PAYLOAD);

        verify(logMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getActionResult()).isEqualTo(LinkageActionResult.SUCCESS);
        assertThat(logCaptor.getValue().getErrorMsg()).isNull();
        verifyNoInteractions(pushService);
    }

    @Test
    @DisplayName("动作分派 CALL_TRANSFER：ward 呼叫域未上线暂存 PENDING（WardUnavailable 注记，Task 12 回接）")
    void callTransferDefersAsPendingWithWardUnavailableNote() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(900016L, LinkageActionType.CALL_TRANSFER, CONDITION_INFUSION)));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);

        executor.onAlarmTriggered(PAYLOAD);

        verify(logMapper).insert(logCaptor.capture());
        IotLinkageLogEntity inserted = logCaptor.getValue();
        assertThat(inserted.getActionResult()).isEqualTo(LinkageActionResult.PENDING);
        assertThat(inserted.getErrorMsg()).contains("WardUnavailable");
        verifyNoInteractions(pushService);
    }

    @Test
    @DisplayName("动作分派 NURSING_TASK：M05 护理任务创建未上线暂存 PENDING（NursingUnavailable 注记，PR-3 回接）")
    void nursingTaskDefersAsPendingWithNursingUnavailableNote() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(900017L, LinkageActionType.NURSING_TASK, CONDITION_INFUSION)));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);

        executor.onAlarmTriggered(PAYLOAD);

        verify(logMapper).insert(logCaptor.capture());
        IotLinkageLogEntity inserted = logCaptor.getValue();
        assertThat(inserted.getActionResult()).isEqualTo(LinkageActionResult.PENDING);
        assertThat(inserted.getErrorMsg()).contains("NursingUnavailable");
    }

    @Test
    @DisplayName("动作分派 WARD_BROADCAST：M16 病区播报域未上线暂存 PENDING（WardBroadcastUnavailable 注记）")
    void wardBroadcastDefersAsPendingWithWardBroadcastUnavailableNote() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(900018L, LinkageActionType.WARD_BROADCAST, CONDITION_INFUSION)));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);

        executor.onAlarmTriggered(PAYLOAD);

        verify(logMapper).insert(logCaptor.capture());
        IotLinkageLogEntity inserted = logCaptor.getValue();
        assertThat(inserted.getActionResult()).isEqualTo(LinkageActionResult.PENDING);
        assertThat(inserted.getErrorMsg()).contains("WardBroadcastUnavailable");
    }

    @Test
    @DisplayName("失败重试：NOTIFY 推送首试失败后恢复——同步快速重试一次成功（retryCount=1）")
    void notifyRetriesOnceThenSucceeds() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(900011L, LinkageActionType.NOTIFY, CONDITION_INFUSION)));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);
        when(alarmMapper.selectOne(any())).thenReturn(alarm());
        doThrow(new IllegalStateException("STOMP 推送失败"))
                .doNothing()
                .when(pushService)
                .pushLinkageNotify(any(IotAlarmEntity.class), anyString());

        executor.onAlarmTriggered(PAYLOAD);

        verify(logMapper).insert(logCaptor.capture());
        IotLinkageLogEntity inserted = logCaptor.getValue();
        assertThat(inserted.getActionResult()).isEqualTo(LinkageActionResult.SUCCESS);
        assertThat(inserted.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败重试耗尽：NOTIFY 推送持续失败——三次快速重试后落 FAILED 终态（可人工重推）")
    void notifyMarksFailedWhenRetriesExhausted() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(900011L, LinkageActionType.NOTIFY, CONDITION_INFUSION)));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);
        when(alarmMapper.selectOne(any())).thenReturn(alarm());
        doThrow(new IllegalStateException("STOMP 推送失败"))
                .when(pushService)
                .pushLinkageNotify(any(IotAlarmEntity.class), anyString());

        executor.onAlarmTriggered(PAYLOAD);

        // 首试+三次重试=四次执行，耗尽落 FAILED 单行留痕
        verify(pushService, times(4)).pushLinkageNotify(any(IotAlarmEntity.class), anyString());
        verify(logMapper).insert(logCaptor.capture());
        IotLinkageLogEntity inserted = logCaptor.getValue();
        assertThat(inserted.getActionResult()).isEqualTo(LinkageActionResult.FAILED);
        assertThat(inserted.getRetryCount()).isEqualTo(3);
        assertThat(inserted.getErrorMsg()).contains("STOMP 推送失败");
        verify(events).publishEvent(eventCaptor.capture());
        LinkageExecutedPayload payload =
                (LinkageExecutedPayload) eventCaptor.getValue().payload();
        assertThat(payload.actionResult()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("列宽防线：重试耗尽 error_msg 超长截断至 VARCHAR(500) 内")
    void truncatesErrorMsgToColumnLimit() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(900011L, LinkageActionType.NOTIFY, CONDITION_INFUSION)));
        when(seqGate.nextLinkageNo()).thenReturn(LINKAGE_NO);
        when(alarmMapper.selectOne(any())).thenReturn(alarm());
        doThrow(new IllegalStateException("失败原因".repeat(200)))
                .when(pushService)
                .pushLinkageNotify(any(IotAlarmEntity.class), anyString());

        executor.onAlarmTriggered(PAYLOAD);

        verify(logMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getErrorMsg()).hasSizeLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("人工重推路径：execute 复用既有联动号执行动作，不落行不发布不取号（留痕归服务层 CAS）")
    void executeReusesGivenLinkageNoWithoutRecording() {
        IotLinkageRuleEntity notifyRule = rule(900011L, LinkageActionType.NOTIFY, CONDITION_INFUSION);
        when(alarmMapper.selectOne(any())).thenReturn(alarm());

        LinkageExecutor.ActionExecution outcome = executor.execute(notifyRule, ALARM_NO, LINKAGE_NO);

        assertThat(outcome.result()).isEqualTo(LinkageActionResult.SUCCESS);
        assertThat(outcome.retryCount()).isZero();
        verify(pushService).pushLinkageNotify(any(IotAlarmEntity.class), eq(LINKAGE_NO));
        verifyNoInteractions(logMapper, events, seqGate);
    }

    /** 构造启用状态的联动规则（触发源固定 ALARM_TRIGGERED——P2 唯一接线源） */
    private static IotLinkageRuleEntity rule(long id, LinkageActionType actionType, String conditionJson) {
        IotLinkageRuleEntity rule = new IotLinkageRuleEntity();
        rule.setId(id);
        rule.setRuleName("联动规则" + id);
        rule.setTriggerSource(LinkageTriggerSource.ALARM_TRIGGERED);
        rule.setTriggerCondition(conditionJson);
        rule.setActionType(actionType);
        rule.setEnabled(true);
        return rule;
    }

    /** 构造告警行（NOTIFY 动作定位与 WS 路由的取数载体，wardId 供主题路由） */
    private static IotAlarmEntity alarm() {
        IotAlarmEntity alarm = new IotAlarmEntity();
        alarm.setAlarmNo(ALARM_NO);
        alarm.setRuleId(77L);
        alarm.setDeviceId(DEVICE_ID);
        alarm.setPatientId(5L);
        alarm.setVisitId("20260901000001");
        alarm.setWardId(WARD_ID);
        alarm.setAlarmLevel(AlarmLevel.CRITICAL);
        alarm.setMetricCode(METRIC_CODE);
        alarm.setTriggerValue("80");
        alarm.setLastTriggeredAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return alarm;
    }
}
