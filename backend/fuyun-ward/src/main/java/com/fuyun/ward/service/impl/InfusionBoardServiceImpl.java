package com.fuyun.ward.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.iot.api.IotTelemetryQueryPort;
import com.fuyun.iot.api.TelemetryPoint;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.entity.WardCallEntity;
import com.fuyun.ward.enums.CallStatus;
import com.fuyun.ward.enums.CallType;
import com.fuyun.ward.mapper.WardCallMapper;
import com.fuyun.ward.service.IInfusionBoardService;
import com.fuyun.ward.vo.InfusionBoardDeviceVO;
import com.fuyun.ward.vo.InfusionBoardVO;
import com.fuyun.ward.vo.InfusionHistoryVO;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 输液看板服务实现（FU-M16 输液监控编排）：病区维度余量/滴速聚合与三档告警映射 + 设备维度
 * 输液历史追溯。
 *
 * <p><b>Port 能力实测结论</b>：IotTelemetryQueryPort 仅暴露 series 单设备单指标时序查询（无
 * 单点 latest 面与病区维度查询面），最新值以 series 末点（last 聚合值）取——brief 预案分支；
 * 设备→病区圈定经本模块活跃 INFUSION 呼叫行（ward 禁跨模块读 iot 绑定表，宪法 B.2-2）。
 *
 * <p>5ml 红档的系统级呼叫落行不在本服务：由 IotAlarmEventListener 消费 iot.alarm.triggered
 * 判定落行（消费侧幂等防重，看板纯读视图）。
 *
 * <p>装配归 WardWebConfig @Import（com.fuyun.ward 不在组件扫描范围，宪法 B.1）。
 */
@Slf4j
public class InfusionBoardServiceImpl implements IInfusionBoardService {

    /** 看板最新值查询窗口（分钟）：30 分钟遥测窗取末点，覆盖攒批消费典型间隔 */
    private static final long BOARD_WINDOW_MINUTES = 30;

    /** 输液历史追溯窗口（分钟）：24 小时曲线（Port 自动路由 1 分钟聚合档） */
    private static final long HISTORY_WINDOW_MINUTES = 24 * 60;

    /** 输液历史告警聚合缺位注记（iot/api 无告警查询端口——实测结论，P1 接口面补齐） */
    private static final String HISTORY_NOTE =
            "告警列表聚合缺位：iot/api 无告警查询端口（禁跨模块读表），告警聚合归 P1 经接口面补齐；" + "实时告警面请订阅 /topic/iot/alarm/{wardId}（iot 推送复用）";

    private final IotTelemetryQueryPort telemetryPort;

    private final WardCallMapper callMapper;

    /**
     * 全参构造器（装配归 WardWebConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param telemetryPort 遥测查询端口（iot api 面实现），非空；余量/滴速曲线消费面
     * @param callMapper    呼叫行 mapper，非空；活跃输液呼叫圈定看板设备集合
     */
    public InfusionBoardServiceImpl(IotTelemetryQueryPort telemetryPort, WardCallMapper callMapper) {
        this.telemetryPort = telemetryPort;
        this.callMapper = callMapper;
    }

    /**
     * 病区输液看板聚合（纯读视图）：活跃输液呼叫圈定设备集合 → 逐设备余量/滴速最新值
     * （series 末点 last 聚合值）→ 三档告警映射（15ml 黄/10ml 橙/5ml 红——展示口径）。
     *
     * <p>边界条件：设备集合由本模块活跃 INFUSION 呼叫行派生（非 iot 绑定表——宪法 B.2-2
     * 禁跨模块读表）；单设备遥测查询失败降级为空曲线不阻断整板（warn 留痕）；无活跃呼叫
     * 时出空设备清单；5ml 红档系统级呼叫落行归事件消费链，看板不重复触发。
     *
     * @param wardId 病区 ID，非空；来源：看板端点路径变量
     * @return 看板视图（设备行清单；无遥测数据的设备行余量/滴速为 null、档位 NONE）
     */
    @Override
    @Transactional(readOnly = true)
    public InfusionBoardVO board(Long wardId) {
        // 数据库读操作：活跃输液呼叫圈定病区输液设备集合（呼叫行即当前监控对象，去重保序）
        List<WardCallEntity> activeCalls = callMapper.selectList(Wrappers.<WardCallEntity>lambdaQuery()
                .eq(WardCallEntity::getWardId, wardId)
                .eq(WardCallEntity::getCallType, CallType.INFUSION)
                .in(
                        WardCallEntity::getStatus,
                        List.of(
                                CallStatus.CREATED,
                                CallStatus.ANSWERED,
                                CallStatus.IN_PROGRESS,
                                CallStatus.TRANSFERRED)));
        Set<String> deviceIds = new LinkedHashSet<>();
        for (WardCallEntity call : activeCalls) {
            if (call.getDeviceId() != null && !call.getDeviceId().isBlank()) {
                deviceIds.add(call.getDeviceId());
            }
        }
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusMinutes(BOARD_WINDOW_MINUTES);
        List<InfusionBoardDeviceVO> devices = deviceIds.stream()
                .map(deviceId -> buildDeviceRow(deviceId, from, to))
                .toList();
        log.debug("输液看板聚合完成：wardId={}，devices={}", wardId, deviceIds);
        return new InfusionBoardVO(wardId, devices);
    }

    /**
     * 设备维度输液历史追溯：24 小时余量/滴速双曲线（Port 自动路由聚合档位——超 24h 强制
     * 1 小时档，当前窗口固定 24h 走 raw/1min 判定）。
     *
     * <p>边界条件：告警列表聚合缺位（iot/api 无告警查询端口——实测结论），以固定注记随出参
     * 透出（P1 接口面补齐；实时告警面订阅 /topic/iot/alarm/{wardId}）；曲线查询异常降级
     * 空曲线不阻断（warn 留痕）。
     *
     * @param deviceId IoTDA 设备标识，非空；来源：历史端点路径变量
     * @return 历史视图（双曲线 + 告警缺位注记；曲线时序点 time 升序）
     */
    @Override
    @Transactional(readOnly = true)
    public InfusionHistoryVO history(String deviceId) {
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusMinutes(HISTORY_WINDOW_MINUTES);
        // 第三方接口调用（跨模块端口）：余量/滴速双曲线（Port 自动路由：超 24h 强制 1 小时聚合档）
        List<TelemetryPoint> remainSeries =
                series(deviceId, WardMessagingConstants.INFUSION_SHORTAGE_METRIC_CODE, from, to);
        List<TelemetryPoint> dropSeries =
                series(deviceId, WardMessagingConstants.INFUSION_DROP_RATE_METRIC_CODE, from, to);
        log.info(
                "输液历史追溯完成：deviceId={}，remainPoints={}，dropPoints={}", deviceId, remainSeries.size(), dropSeries.size());
        return new InfusionHistoryVO(deviceId, remainSeries, dropSeries, HISTORY_NOTE);
    }

    /**
     * 单设备行装配：余量/滴速最新值（series 末点 last 聚合值）+ 三档告警映射。
     *
     * @param deviceId 设备号，非空
     * @param from     窗口起点，非空
     * @param to       窗口终点，非空
     * @return 设备行视图，非空（无遥测数据时余量/滴速为 null、档位 NONE）
     */
    private InfusionBoardDeviceVO buildDeviceRow(String deviceId, OffsetDateTime from, OffsetDateTime to) {
        List<TelemetryPoint> remain = series(deviceId, WardMessagingConstants.INFUSION_SHORTAGE_METRIC_CODE, from, to);
        List<TelemetryPoint> drop = series(deviceId, WardMessagingConstants.INFUSION_DROP_RATE_METRIC_CODE, from, to);
        BigDecimal remainLatest = tailLast(remain);
        BigDecimal dropLatest = tailLast(drop);
        // 三档映射（brief 冻结 15/10/5——阈值参照 iot alarm_rule 配置面快照，展示口径）
        return new InfusionBoardDeviceVO(
                deviceId, remainLatest, dropLatest, InfusionBoardDeviceVO.mapAlertLevel(remainLatest));
    }

    /**
     * 时序查询容错包装（看板聚合不因单设备曲线失败整体失败——逐设备降级为空曲线）。
     *
     * @param deviceId   设备号，非空
     * @param metricCode 指标编码，非空
     * @param from       窗口起点，非空
     * @param to         窗口终点，非空
     * @return 时序点清单，非空；查询异常降级为空清单（warn 留痕）
     */
    private List<TelemetryPoint> series(String deviceId, String metricCode, OffsetDateTime from, OffsetDateTime to) {
        try {
            // 第三方接口调用（跨模块端口）：自动档位路由（≤24h raw/1min 由 Port 内部判定）
            return telemetryPort.series(deviceId, metricCode, from, to, null);
        } catch (RuntimeException e) {
            log.warn("输液遥测曲线查询失败（降级为空曲线）：deviceId={}，metricCode={}，reason={}", deviceId, metricCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * 末点最新值提取（Port 无单点 latest 查询面——series 末点 last 聚合值承载最新值语义）。
     *
     * @param points 时序点清单（time 升序），非空
     * @return 末点 last 聚合值；空清单为 null
     */
    private static BigDecimal tailLast(List<TelemetryPoint> points) {
        return points.isEmpty() ? null : points.get(points.size() - 1).last();
    }
}
