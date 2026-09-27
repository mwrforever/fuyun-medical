package com.fuyun.ward.service.impl;

import static com.fuyun.ward.constants.WardMessagingConstants.INFUSION_DROP_RATE_METRIC_CODE;
import static com.fuyun.ward.constants.WardMessagingConstants.INFUSION_SHORTAGE_METRIC_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.iot.api.IotTelemetryQueryPort;
import com.fuyun.iot.api.TelemetryPoint;
import com.fuyun.ward.entity.WardCallEntity;
import com.fuyun.ward.enums.CallSource;
import com.fuyun.ward.enums.CallStatus;
import com.fuyun.ward.enums.CallType;
import com.fuyun.ward.mapper.WardCallMapper;
import com.fuyun.ward.service.IInfusionBoardService;
import com.fuyun.ward.vo.InfusionBoardDeviceVO;
import com.fuyun.ward.vo.InfusionBoardVO;
import com.fuyun.ward.vo.InfusionHistoryVO;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 输液看板服务单测（P2 PR-2 Task 12 Step 5，TDD 先红后绿）：余量/滴速最新值聚合（Port series
 * 末点——Port 无单点 latest 查询面，实测结论注记）、三档告警映射（15/10/5）、活跃呼叫圈定设备、
 * 输液历史追溯骨架（告警聚合缺位注记）。
 */
@ExtendWith(MockitoExtension.class)
class InfusionBoardServiceImplTest {

    @Mock
    private IotTelemetryQueryPort telemetryPort;

    @Mock
    private WardCallMapper callMapper;

    private IInfusionBoardService service;

    @BeforeAll
    static void initTableInfo() {
        // MP 实体元数据初始化（selectList wrapper 依赖 TableInfo，iot 单测同款）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WardCallEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new InfusionBoardServiceImpl(telemetryPort, callMapper);
    }

    @Test
    @DisplayName("看板聚合：活跃输液呼叫圈定设备，series 末点取余量/滴速最新值")
    void boardAggregatesLatestRemainAndDropRateFromSeriesTail() {
        when(callMapper.selectList(any())).thenReturn(List.of(infusionCall("dev-pump-1")));
        when(telemetryPort.series(
                        eq("dev-pump-1"),
                        eq(INFUSION_SHORTAGE_METRIC_CODE),
                        any(OffsetDateTime.class),
                        any(OffsetDateTime.class),
                        any()))
                .thenReturn(List.of(
                        point("dev-pump-1", INFUSION_SHORTAGE_METRIC_CODE, "30"),
                        point("dev-pump-1", INFUSION_SHORTAGE_METRIC_CODE, "8")));
        // 双指标查询（余量+滴速）：滴速曲线无数据为空清单（VO 滴速可空语义）
        when(telemetryPort.series(
                        eq("dev-pump-1"),
                        eq(INFUSION_DROP_RATE_METRIC_CODE),
                        any(OffsetDateTime.class),
                        any(OffsetDateTime.class),
                        any()))
                .thenReturn(List.of());

        InfusionBoardVO vo = service.board(1001L);

        assertThat(vo.devices()).hasSize(1);
        assertThat(vo.devices().get(0).deviceId()).isEqualTo("dev-pump-1");
        assertThat(vo.devices().get(0).remainLatest())
                .as("余量取 series 末点 last 聚合值（Port 无单点 latest 查询面）")
                .isEqualByComparingTo("8");
        assertThat(vo.devices().get(0).dropRateLatest()).isNull();
    }

    @Test
    @DisplayName("三档映射：≤5 红/≤10 橙/≤15 黄/超 15 与无数据 NONE（RED 优先判定）")
    void alertLevelMappingFollowsFrozenThresholds() {
        assertThat(InfusionBoardDeviceVO.mapAlertLevel(new BigDecimal("4"))).isEqualTo(InfusionBoardDeviceVO.LEVEL_RED);
        assertThat(InfusionBoardDeviceVO.mapAlertLevel(new BigDecimal("5"))).isEqualTo(InfusionBoardDeviceVO.LEVEL_RED);
        assertThat(InfusionBoardDeviceVO.mapAlertLevel(new BigDecimal("8")))
                .isEqualTo(InfusionBoardDeviceVO.LEVEL_ORANGE);
        assertThat(InfusionBoardDeviceVO.mapAlertLevel(new BigDecimal("12")))
                .isEqualTo(InfusionBoardDeviceVO.LEVEL_YELLOW);
        assertThat(InfusionBoardDeviceVO.mapAlertLevel(new BigDecimal("16")))
                .isEqualTo(InfusionBoardDeviceVO.LEVEL_NONE);
        assertThat(InfusionBoardDeviceVO.mapAlertLevel(null)).isEqualTo(InfusionBoardDeviceVO.LEVEL_NONE);
    }

    @Test
    @DisplayName("看板聚合：病区无活跃输液呼叫为空设备清单（零 Port 调用）")
    void boardWithoutActiveInfusionCallsIsEmpty() {
        when(callMapper.selectList(any())).thenReturn(List.of());

        InfusionBoardVO vo = service.board(1001L);

        assertThat(vo.wardId()).isEqualTo(1001L);
        assertThat(vo.devices()).isEmpty();
    }

    @Test
    @DisplayName("输液历史：余量/滴速双曲线透传，告警聚合缺位注记透出（P1 接口面补齐申报）")
    void historyReturnsSeriesWithAggregationNote() {
        when(telemetryPort.series(
                        eq("dev-pump-1"), anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class), any()))
                .thenReturn(List.of(
                        point("dev-pump-1", INFUSION_SHORTAGE_METRIC_CODE, "100"),
                        point("dev-pump-1", INFUSION_SHORTAGE_METRIC_CODE, "60")));

        InfusionHistoryVO vo = service.history("dev-pump-1");

        assertThat(vo.deviceId()).isEqualTo("dev-pump-1");
        assertThat(vo.remainSeries()).hasSize(2);
        assertThat(vo.note()).isNotBlank();
    }

    @Test
    @DisplayName("遥测查询失败降级：单设备曲线异常不阻断看板（降级为空曲线、档位 NONE）")
    void boardDegradesToEmptyRowWhenPortFails() {
        when(callMapper.selectList(any())).thenReturn(List.of(infusionCall("dev-pump-1")));
        when(telemetryPort.series(
                        anyString(), anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class), any()))
                .thenThrow(new IllegalStateException("端口超时"));

        InfusionBoardVO vo = service.board(1001L);

        assertThat(vo.devices()).hasSize(1);
        assertThat(vo.devices().get(0).remainLatest()).isNull();
        assertThat(vo.devices().get(0).alertLevel()).isEqualTo(InfusionBoardDeviceVO.LEVEL_NONE);
    }

    /** 输液呼叫行夹具（活跃输液档，source_ref=告警号） */
    private static WardCallEntity infusionCall(String deviceId) {
        WardCallEntity entity = new WardCallEntity();
        entity.setId(1L);
        entity.setCallNo("CALL2026092600001");
        entity.setWardId(1001L);
        entity.setDeviceId(deviceId);
        entity.setCallType(CallType.INFUSION);
        entity.setSource(CallSource.IOT);
        entity.setStatus(CallStatus.CREATED);
        entity.setEscalationCount(0);
        entity.setSourceRef("AL2026092600001");
        return entity;
    }

    /** 遥测点夹具（last 聚合值承载最新值语义） */
    private static TelemetryPoint point(String deviceId, String metricCode, String last) {
        return new TelemetryPoint(
                deviceId,
                metricCode,
                OffsetDateTime.now(),
                new BigDecimal(last),
                new BigDecimal(last),
                new BigDecimal(last),
                new BigDecimal(last),
                new BigDecimal(last),
                1L);
    }
}
