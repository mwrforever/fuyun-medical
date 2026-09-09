package com.fuyun.common.messaging;

import java.time.Instant;

/**
 * CF-7 标准遥测消息模型：四路接入（模式 A/B/C/D）遥测管道的统一语言（M14 FU-M14-05"四路同构"）。
 *
 * <p>落 common 依据（BRIEF-PR2-01 §1.2）：本模型是遥测移交 SPI 的签名载体（M20 Spec §3.4——M14 注册
 * 实现、M20 调用），须与未来 SPI 同层定义，避免 PR-4 跨模块移类；各模块仅依赖 common 即可承载遥测消息。
 *
 * <p>P0 只登记不消费：本 record 仅随 V5 种子迁移在 event_registry 落契约行（iot.telemetry.message），
 * 不建 SPI 接口、不建任何 iot 消费代码——SPI 与消费链路随 PR-4 交付。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变），equals/hashCode 直接采用 record 语义。
 *
 * @param deviceId   设备号（医疗设备唯一标识），非空；来源：设备档案（M14）
 * @param metricCode 指标编码（MDC 术语编码），非空；来源：解析校验五步之术语映射后的标准编码
 * @param value      原始值字符串承载（防管道期精度/格式约定漂移，数值解析定型随 PR-4），非空；来源：遥测报文原文
 * @param unit       单位（如 bpm/%/mmHg），允许为空（无量纲指标）；来源：设备上报或术语映射
 * @param occurredAt 发生时刻（设备端或网关时间，UTC 语义），非空；来源：遥测报文
 * @param quality    数据质量标记：GOOD 正常 / SUSPECT 可疑（时间偏差超阈值等，标注不丢弃）/ BAD 异常
 *                   （生理极限硬校验命中）；来源：解析校验五步
 * @param source     接入链路来源：IOTDA 主链路 / HL7 模式 D 辅链路；来源：网关接入层
 */
public record StandardTelemetryMessage(
        String deviceId,
        String metricCode,
        String value,
        String unit,
        Instant occurredAt,
        String quality,
        String source) {}
