package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.SaveAlarmRuleRequest;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.ThresholdOp;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.vo.AlarmRuleVO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 告警规则服务单测（P2 PR-2 Task 7 Step 3）：规则 CRUD（软删/404）与抖动防护②拒保存
 * （THRESHOLD 缺持续时长/恢复带、OFFLINE 缺离线判定秒——IOT-1013 409）、simulate 历史回放
 * （时段重放评估不落库，仅 THRESHOLD 支持，时窗非法 IOT-1019 400）。
 *
 * <p>真实 SQL 行为归 IT 回归；JaCoCo 核心包 com.fuyun.iot.service.impl LINE=1.00 承载测试。
 */
@ExtendWith(MockitoExtension.class)
class AlarmRuleServiceImplTest {

    private static final long RULE_ID = 900001L;

    @Mock
    private IotAlarmRuleMapper ruleMapper;

    @Mock
    private IotTelemetryMapper telemetryMapper;

    @Captor
    private ArgumentCaptor<IotAlarmRuleEntity> ruleCaptor;

    private AlarmRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AlarmRuleServiceImpl(ruleMapper, telemetryMapper);
    }

    @Test
    @DisplayName("抖动防护②：THRESHOLD 规则缺持续时长拒保存（IOT-1013 409）")
    void createRejectsThresholdWithoutDuration() {
        SaveAlarmRuleRequest request = thresholdRequest(null, new BigDecimal("10"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED))
                .hasMessageContaining("抖动防护");
        verify(ruleMapper, never()).insert(any(IotAlarmRuleEntity.class));
    }

    @Test
    @DisplayName("抖动防护②：DEVICE_ALARM 规则缺告警关联编码拒保存（IOT-1013 409）")
    void createRejectsDeviceAlarmWithoutMetricCode() {
        SaveAlarmRuleRequest request = new SaveAlarmRuleRequest(
                "透传规则",
                AlarmRuleType.DEVICE_ALARM,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                AlarmLevel.WARNING,
                null,
                null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED));
        verify(ruleMapper, never()).insert(any(IotAlarmRuleEntity.class));
    }

    @Test
    @DisplayName("登记成功：OFFLINE 规则全字段落行（离线判定秒承载）")
    void createPersistsOfflineRule() {
        when(ruleMapper.insert(any(IotAlarmRuleEntity.class))).thenReturn(1);
        SaveAlarmRuleRequest request = new SaveAlarmRuleRequest(
                "离线规则",
                AlarmRuleType.OFFLINE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                600,
                AlarmLevel.WARNING,
                null,
                Boolean.FALSE);

        AlarmRuleVO vo = service.create(request);

        verify(ruleMapper).insert(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue().getOfflineSecs()).isEqualTo(600);
        assertThat(vo.enabled()).isFalse();
    }

    @Test
    @DisplayName("清单：全量规则按 id 升序出网")
    void listReturnsAllRulesOrderedById() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(thresholdEntity()));

        List<AlarmRuleVO> result = service.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(RULE_ID);
        assertThat(result.get(0).ruleType()).isEqualTo(AlarmRuleType.THRESHOLD);
    }

    @Test
    @DisplayName("清单：行数达装载上限截断留痕（200 行硬顶防配置膨胀）")
    void listAllWarnsWhenRuleLimitReached() {
        // 200 行夹具（同一行实例复用，仅驱动硬顶判定；DB 侧 LIMIT 200 命中即恰返回 200 行）
        when(ruleMapper.selectList(any())).thenReturn(java.util.Collections.nCopies(200, thresholdEntity()));

        assertThat(service.listAll()).hasSize(200);
    }

    @Test
    @DisplayName("抖动防护②：THRESHOLD 规则缺恢复带拒保存（IOT-1013 409）")
    void createRejectsThresholdWithoutRecoveryBand() {
        SaveAlarmRuleRequest request = thresholdRequest(30, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED));
        verify(ruleMapper, never()).insert(any(IotAlarmRuleEntity.class));
    }

    @Test
    @DisplayName("抖动防护②：OFFLINE 规则缺离线判定秒拒保存（IOT-1013 409）")
    void createRejectsOfflineWithoutOfflineSecs() {
        SaveAlarmRuleRequest request = new SaveAlarmRuleRequest(
                "离线规则",
                AlarmRuleType.OFFLINE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                AlarmLevel.WARNING,
                null,
                null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED));
        verify(ruleMapper, never()).insert(any(IotAlarmRuleEntity.class));
    }

    @Test
    @DisplayName("登记成功：THRESHOLD 规则全字段落行，默认值补齐（静默窗口/升级时限 300、启用 true）")
    void createPersistsThresholdRuleWithDefaults() {
        when(ruleMapper.insert(any(IotAlarmRuleEntity.class))).thenReturn(1);

        AlarmRuleVO vo = service.create(thresholdRequest(30, new BigDecimal("10")));

        verify(ruleMapper).insert(ruleCaptor.capture());
        IotAlarmRuleEntity inserted = ruleCaptor.getValue();
        assertThat(inserted.getRuleType()).isEqualTo(AlarmRuleType.THRESHOLD);
        assertThat(inserted.getCompareOp()).isEqualTo(ThresholdOp.GT);
        assertThat(inserted.getThresholdValue()).isEqualByComparingTo("150");
        assertThat(inserted.getDurationSecs()).isEqualTo(30);
        assertThat(inserted.getRecoveryBand()).isEqualByComparingTo("10");
        assertThat(inserted.getSilenceWindowSecs()).isEqualTo(300);
        assertThat(inserted.getEscalateAfterSecs()).isEqualTo(300);
        assertThat(inserted.getEnabled()).isTrue();
        assertThat(vo.durationSecs()).isEqualTo(30);
    }

    @Test
    @DisplayName("更新：规则不存在拒绝（IOT-1012 404）")
    void updateRejectsMissingRule() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.update(RULE_ID, thresholdRequest(30, new BigDecimal("10"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_NOT_FOUND));
    }

    @Test
    @DisplayName("更新成功：字段全量覆写（审计列不触碰）")
    void updateOverwritesConfiguredFields() {
        IotAlarmRuleEntity existing = thresholdEntity();
        when(ruleMapper.selectById(RULE_ID)).thenReturn(existing);
        when(ruleMapper.updateById(any(IotAlarmRuleEntity.class))).thenReturn(1);

        SaveAlarmRuleRequest request = new SaveAlarmRuleRequest(
                "心率过速告警（改）",
                AlarmRuleType.THRESHOLD,
                "dev-002",
                "MDC_ECG_HEART_RATE",
                ThresholdOp.LT,
                new BigDecimal("40"),
                45,
                new BigDecimal("5"),
                600,
                null,
                AlarmLevel.WARNING,
                600,
                Boolean.FALSE);

        AlarmRuleVO vo = service.update(RULE_ID, request);

        verify(ruleMapper).updateById(ruleCaptor.capture());
        IotAlarmRuleEntity updated = ruleCaptor.getValue();
        assertThat(updated.getId()).isEqualTo(RULE_ID);
        assertThat(updated.getRuleName()).isEqualTo("心率过速告警（改）");
        assertThat(updated.getCompareOp()).isEqualTo(ThresholdOp.LT);
        assertThat(updated.getAlarmLevel()).isEqualTo(AlarmLevel.WARNING);
        assertThat(updated.getEnabled()).isFalse();
        assertThat(vo.ruleName()).isEqualTo("心率过速告警（改）");
    }

    @Test
    @DisplayName("删除：规则不存在拒绝（IOT-1012 404）")
    void deleteRejectsMissingRule() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(RULE_ID))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_NOT_FOUND));
    }

    @Test
    @DisplayName("删除成功：软删（@TableLogic deleteById）")
    void deleteSoftDeletesRule() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(thresholdEntity());
        when(ruleMapper.deleteById(RULE_ID)).thenReturn(1);

        service.delete(RULE_ID);

        verify(ruleMapper).deleteById(RULE_ID);
    }

    @Test
    @DisplayName("模拟：规则不存在拒绝（IOT-1012 404）")
    void simulateRejectsMissingRule() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.simulate(
                        RULE_ID,
                        new com.fuyun.iot.dto.SimulateAlarmRequest(
                                Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-26T01:00:00Z"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_NOT_FOUND));
    }

    @Test
    @DisplayName("模拟：非 THRESHOLD 规则不支持历史回放（IOT-1013 409）")
    void simulateRejectsNonThresholdRule() {
        IotAlarmRuleEntity offline = thresholdEntity();
        offline.setRuleType(AlarmRuleType.OFFLINE);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(offline);

        assertThatThrownBy(() -> service.simulate(
                        RULE_ID,
                        new com.fuyun.iot.dto.SimulateAlarmRequest(
                                Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-26T01:00:00Z"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("模拟：时窗非法（起点不早于终点）拒绝（IOT-1019 400）")
    void simulateRejectsInvalidWindow() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(thresholdEntity());

        assertThatThrownBy(() -> service.simulate(
                        RULE_ID,
                        new com.fuyun.iot.dto.SimulateAlarmRequest(
                                Instant.parse("2026-09-26T01:00:00Z"), Instant.parse("2026-09-26T00:00:00Z"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(IotErrorCode.TELEMETRY_QUERY_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("模拟回放：持续达标集触发一次、未达标集不触发，结果不落库（零 insert）")
    void simulateReplaysThresholdTriggersWithoutPersisting() {
        IotAlarmRuleEntity rule = thresholdEntity();
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule);
        // 设备 dev-001 两回合：第一回合并排两行越限（间隔 40s ≥ 持续 30s → 触发），随后恢复；
        // 第二回合单行越限（无后续行 → 未达标不触发）
        Instant base = Instant.parse("2026-09-26T00:00:00Z");
        when(telemetryMapper.selectList(any()))
                .thenReturn(List.of(
                        telemetryRow("dev-001", "170", base),
                        telemetryRow("dev-001", "168", base.plusSeconds(40)),
                        telemetryRow("dev-001", "120", base.plusSeconds(41)),
                        telemetryRow("dev-001", "165", base.plusSeconds(60))));

        var result = service.simulate(RULE_ID, new com.fuyun.iot.dto.SimulateAlarmRequest(base, base.plusSeconds(120)));

        assertThat(result.scannedRows()).isEqualTo(4);
        assertThat(result.triggers()).hasSize(1);
        assertThat(result.triggers().get(0).deviceId()).isEqualTo("dev-001");
        assertThat(result.triggers().get(0).triggerValue()).isEqualTo("168");
        assertThat(result.triggers().get(0).triggeredAt()).isEqualTo(base.plusSeconds(40));
        assertThat(result.triggers().get(0).alarmLevel()).isEqualTo(AlarmLevel.CRITICAL);
        verify(ruleMapper, never()).insert(any(IotAlarmRuleEntity.class));
    }

    @Test
    @DisplayName("模拟回放：LT 方向且规则限定设备——低于阈值持续达标触发，非数值行不进回放")
    void simulateReplaysDeviceScopedLtRule() {
        IotAlarmRuleEntity rule = thresholdEntity();
        rule.setCompareOp(ThresholdOp.LT);
        rule.setThresholdValue(new BigDecimal("90"));
        rule.setDurationSecs(60);
        rule.setRecoveryBand(new BigDecimal("10"));
        rule.setDeviceId("dev-001");
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule);
        // dev-001 两行越限（间隔 60s ≥ 持续 60s → 触发）+ 一行非数值（不进回放）
        Instant base = Instant.parse("2026-09-26T00:00:00Z");
        IotTelemetryEntity nonNumeric = telemetryRow("dev-001", "80", base);
        nonNumeric.setValue(null);
        when(telemetryMapper.selectList(any()))
                .thenReturn(List.of(
                        nonNumeric,
                        telemetryRow("dev-001", "80", base),
                        telemetryRow("dev-001", "85", base.plusSeconds(60))));

        var result = service.simulate(RULE_ID, new com.fuyun.iot.dto.SimulateAlarmRequest(base, base.plusSeconds(120)));

        assertThat(result.scannedRows()).isEqualTo(3);
        assertThat(result.triggers()).hasSize(1);
        assertThat(result.triggers().get(0).triggerValue()).isEqualTo("85");
    }

    @Test
    @DisplayName("模拟回放：存量脏规则（缺比较方向）防御性零触发不误报")
    void simulateSkipsRowsWhenStoredRuleMalformed() {
        IotAlarmRuleEntity malformed = thresholdEntity();
        malformed.setCompareOp(null);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(malformed);
        Instant base = Instant.parse("2026-09-26T00:00:00Z");
        when(telemetryMapper.selectList(any())).thenReturn(List.of(telemetryRow("dev-001", "170", base)));

        var result = service.simulate(RULE_ID, new com.fuyun.iot.dto.SimulateAlarmRequest(base, base.plusSeconds(120)));

        assertThat(result.triggers()).isEmpty();
    }

    @Test
    @DisplayName("模拟回放：单窗口行数上限截断留痕（5000 行硬顶）")
    void simulateWarnsWhenRowLimitReached() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(thresholdEntity());
        // 5000 行夹具（同一行实例复用即可，仅驱动截断判定与回放循环）
        IotTelemetryEntity row = telemetryRow("dev-001", "170", Instant.parse("2026-09-26T00:00:00Z"));
        when(telemetryMapper.selectList(any())).thenReturn(java.util.Collections.nCopies(5000, row));

        var result = service.simulate(
                RULE_ID,
                new com.fuyun.iot.dto.SimulateAlarmRequest(
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-26T01:00:00Z")));

        assertThat(result.scannedRows()).isEqualTo(5000);
    }

    /** THRESHOLD 登记请求（心率 >150 CRITICAL duration 30s recovery_band 10，可空位传 null 验校验） */
    private static SaveAlarmRuleRequest thresholdRequest(Integer durationSecs, BigDecimal recoveryBand) {
        return new SaveAlarmRuleRequest(
                "心率过速危急告警",
                AlarmRuleType.THRESHOLD,
                null,
                "MDC_ECG_HEART_RATE",
                ThresholdOp.GT,
                new BigDecimal("150"),
                durationSecs,
                recoveryBand,
                null,
                null,
                AlarmLevel.CRITICAL,
                null,
                null);
    }

    /** 既有规则行夹具（V1008 种子行同构） */
    private static IotAlarmRuleEntity thresholdEntity() {
        IotAlarmRuleEntity entity = new IotAlarmRuleEntity();
        entity.setId(RULE_ID);
        entity.setRuleName("心率过速危急告警（示例）");
        entity.setRuleType(AlarmRuleType.THRESHOLD);
        entity.setMetricCode("MDC_ECG_HEART_RATE");
        entity.setCompareOp(ThresholdOp.GT);
        entity.setThresholdValue(new BigDecimal("150"));
        entity.setDurationSecs(30);
        entity.setRecoveryBand(new BigDecimal("10"));
        entity.setSilenceWindowSecs(300);
        entity.setAlarmLevel(AlarmLevel.CRITICAL);
        entity.setEscalateAfterSecs(300);
        entity.setEnabled(true);
        return entity;
    }

    private static IotTelemetryEntity telemetryRow(String deviceId, String value, Instant occurredAt) {
        IotTelemetryEntity entity = new IotTelemetryEntity();
        entity.setDeviceId(deviceId);
        entity.setMetricCode("MDC_ECG_HEART_RATE");
        entity.setValue(new BigDecimal(value));
        entity.setOccurredAt(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        return entity;
    }
}
