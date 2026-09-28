package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.payload.AlarmClosedPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.AlarmQueryRequest;
import com.fuyun.iot.dto.CloseAlarmRequest;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmStatus;
import com.fuyun.iot.internal.IotDomainEvent;
import com.fuyun.iot.mapper.IotAlarmMapper;
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

/**
 * 告警服务单测（P2 PR-2 Task 7 Step 3）：告警分页（病区/级别/状态过滤）、确认与关闭 CAS
 * （状态机拒绝 409、404 借承注记）、关闭同事务发布 iot.alarm.closed 事件（载荷逐字段核对）。
 * 读时惰性升级不在本服务（挂 AlarmEngine.evaluate 调用点）；真实 SQL 归 IT 回归。
 */
@ExtendWith(MockitoExtension.class)
class AlarmServiceImplTest {

    private static final String ALARM_NO = "AL2026092600001";

    private static final String OPERATOR = "nurse-01";

    @Mock
    private IotAlarmMapper alarmMapper;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<IotDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<IotAlarmEntity>> queryCaptor;

    private AlarmServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotAlarmEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 操作人上下文注入（审计留痕口径）
        com.fuyun.common.context.OperatorContextHolder.set(OPERATOR);
        service = new AlarmServiceImpl(alarmMapper, events);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // 请求结束清理操作人上下文（ThreadLocal 防线程复用泄漏）
        com.fuyun.common.context.OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("分页：病区/级别/状态过滤条件透传（wrapper 携带三列条件），0 基页码换算")
    void pageAppliesWardLevelStatusFilters() {
        IotAlarmEntity row = alarmRow(AlarmStatus.ACTIVE);
        when(alarmMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<IotAlarmEntity> result = invocation.getArgument(0);
            result.setRecords(List.of(row));
            result.setTotal(1);
            return result;
        });

        PageResult<com.fuyun.iot.vo.AlarmVO> result =
                service.page(new AlarmQueryRequest(2, 50, 1001L, AlarmLevel.CRITICAL, AlarmStatus.ACTIVE));

        verify(alarmMapper).selectPage(any(), queryCaptor.capture());
        String sqlSegment = queryCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("ward_id").contains("alarm_level").contains("status");
        // MP 分页 1 基 current：0 基请求页 2 → current 3、size 50
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content().get(0).alarmNo()).isEqualTo(ALARM_NO);
    }

    @Test
    @DisplayName("确认：告警不存在拒绝（IOT-1012 借承 404，消息区分告警行场景）")
    void acknowledgeRejectsMissingAlarm() {
        when(alarmMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.acknowledge(ALARM_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_NOT_FOUND));
    }

    @Test
    @DisplayName("确认：仅 ACTIVE 可确认——CAS 落败拒绝（IOT-1013 借承 409）")
    void acknowledgeRejectsNonActiveAlarm() {
        when(alarmMapper.selectOne(any())).thenReturn(alarmRow(AlarmStatus.CLOSED));
        when(alarmMapper.casAcknowledge(eq(ALARM_NO), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.acknowledge(ALARM_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("确认成功：CAS 置 ACKNOWLEDGED 并回读出网（操作人落确认人）")
    void acknowledgeMarksAlarmAcknowledged() {
        // 首查定位告警行、CAS 后回读（thenReturn 多值序列：首查 ACTIVE、回读 ACKNOWLEDGED）
        IotAlarmEntity acknowledged = alarmRow(AlarmStatus.ACKNOWLEDGED);
        acknowledged.setAcknowledgedBy(OPERATOR);
        when(alarmMapper.selectOne(any())).thenReturn(alarmRow(AlarmStatus.ACTIVE), acknowledged);
        when(alarmMapper.casAcknowledge(eq(ALARM_NO), anyString())).thenReturn(1);

        com.fuyun.iot.vo.AlarmVO vo = service.acknowledge(ALARM_NO);

        verify(alarmMapper).casAcknowledge(ALARM_NO, OPERATOR);
        assertThat(vo.status()).isEqualTo(AlarmStatus.ACKNOWLEDGED);
        assertThat(vo.acknowledgedBy()).isEqualTo(OPERATOR);
    }

    @Test
    @DisplayName("关闭：告警不存在拒绝（IOT-1012 借承 404）")
    void closeRejectsMissingAlarm() {
        when(alarmMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.close(ALARM_NO, new CloseAlarmRequest("误报")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_NOT_FOUND));
    }

    @Test
    @DisplayName("关闭：已关闭终态拒绝（IOT-1013 借承 409）")
    void closeRejectsAlreadyClosedAlarm() {
        when(alarmMapper.selectOne(any())).thenReturn(alarmRow(AlarmStatus.CLOSED));
        when(alarmMapper.casClose(eq(ALARM_NO), anyString(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.close(ALARM_NO, new CloseAlarmRequest("重复关闭")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("关闭成功：CAS 置 CLOSED 且同事务发布 iot.alarm.closed（载荷逐字段核对）")
    void closePublishesClosedEventInSameTransaction() {
        // 首查定位告警行、CAS 后回读（thenReturn 多值序列：首查 ACTIVE、回读 CLOSED）
        when(alarmMapper.selectOne(any())).thenReturn(alarmRow(AlarmStatus.ACTIVE), alarmRow(AlarmStatus.CLOSED));
        when(alarmMapper.casClose(eq(ALARM_NO), eq("处置完成"), anyString())).thenReturn(1);

        com.fuyun.iot.vo.AlarmVO vo = service.close(ALARM_NO, new CloseAlarmRequest("处置完成"));

        verify(alarmMapper).casClose(eq(ALARM_NO), eq("处置完成"), eq(OPERATOR));
        verify(events).publishEvent(eventCaptor.capture());
        IotDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(IotMessagingConstants.EVENT_ALARM_CLOSED);
        AlarmClosedPayload payload = (AlarmClosedPayload) event.payload();
        assertThat(payload.alarmNo()).isEqualTo(ALARM_NO);
        assertThat(payload.deviceId()).isEqualTo("dev-001");
        assertThat(payload.wardId()).isEqualTo(1001L);
        assertThat(payload.closedBy()).isEqualTo(OPERATOR);
        assertThat(payload.closeReason()).isEqualTo("处置完成");
        assertThat(vo.status()).isEqualTo(AlarmStatus.CLOSED);
    }

    /** 告警行夹具（ACTIVE 心率危急告警，绑定快照五元组冗余） */
    private static IotAlarmEntity alarmRow(AlarmStatus status) {
        IotAlarmEntity entity = new IotAlarmEntity();
        entity.setId(1L);
        entity.setAlarmNo(ALARM_NO);
        entity.setRuleId(900001L);
        entity.setDeviceId("dev-001");
        entity.setPatientId(5L);
        entity.setVisitId("20260901000001");
        entity.setWardId(1001L);
        entity.setAlarmLevel(AlarmLevel.CRITICAL);
        entity.setMetricCode("MDC_ECG_HEART_RATE");
        entity.setTriggerValue("170");
        entity.setStatus(status);
        entity.setTriggerCount(1);
        entity.setLastTriggeredAt(OffsetDateTime.ofInstant(java.time.Instant.now(), ZoneOffset.UTC));
        entity.setEscalationCount(0);
        entity.setCreatedAt(OffsetDateTime.ofInstant(java.time.Instant.now(), ZoneOffset.UTC));
        return entity;
    }
}
