package com.fuyun.ward.internal;

import com.fuyun.ward.cache.WardSeqGate;
import com.fuyun.ward.controller.ColdChainController;
import com.fuyun.ward.controller.InfusionBoardController;
import com.fuyun.ward.controller.VitalSignBoardController;
import com.fuyun.ward.controller.WardCallController;
import com.fuyun.ward.service.impl.ColdChainServiceImpl;
import com.fuyun.ward.service.impl.InfusionBoardServiceImpl;
import com.fuyun.ward.service.impl.VitalSignBoardServiceImpl;
import com.fuyun.ward.service.impl.WardCallServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M16 病房域 Web/服务装配集中点（InpatientWebConfig 同款显式 @Import 形态——backend 宪法 B.1
 * 装配归 app：本类由 fuyun-app WardConfig @Import 生效，禁组件扫描放宽；mapper 由既有
 * @MapperScan 按注解自动覆盖，不入本清单）。P2 PR-2 Task 12 交付：业务号发号器（CALL/ARCH/CCR
 * 三通道）、呼叫状态机服务（六态合法迁移唯一裁决面 + 读时惰性升级 + 同床位合并取消）、冷链合规
 * 台账服务（档案 CRUD + 三类型记录登记 + 归档事件发布 + 巡检 overdue 读时惰性判定）、输液看板
 * 服务（余量/滴速聚合 + 三档映射 + 历史追溯）、体征看板服务（anomaly 注记视图）与九/七/二/一
 * 四端点控制器。订阅监听器与发送模板归 {@link WardMessagingConfig}（消息装配集中点）；
 * {@link IotAlarmEventListener}（输液告急落行）与 {@link NursingInfusionCompletedListener}
 * （拔针复位）/ {@link TelemetryAnomalyEventListener}（体征质量注记）一并注册于本清单（消费
 * 入口非消息装配面——IotConfig 先例：IotAlarmEventListener 经 IotConfig @Import 注册）。
 */
@Configuration
@Import({
    WardSeqGate.class,
    WardCallServiceImpl.class,
    ColdChainServiceImpl.class,
    InfusionBoardServiceImpl.class,
    VitalSignBoardServiceImpl.class,
    WardCallController.class,
    ColdChainController.class,
    InfusionBoardController.class,
    VitalSignBoardController.class,
    IotAlarmEventListener.class,
    NursingInfusionCompletedListener.class,
    TelemetryAnomalyEventListener.class
})
public class WardWebConfig {}
