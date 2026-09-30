package com.fuyun.iot.service;

import com.fuyun.common.exception.BizException;
import com.fuyun.iot.dto.FallbackIngestRequest;

/**
 * HTTP 兜底通道入库服务：{@code POST /ingest/iotda-fallback} 的完整入库编排单点
 * （M14 B4.3；EX-15 鉴权与消息组装自 IotFallbackIngestController 下沉承载，宪法 B.1
 * 「controller 仅入参校验 + 调用 service + 编排响应」）。
 *
 * <p>执行链：独立鉴权判定（{@code X-Iot-Fallback-Token} 共享密钥常量时间比对，失败统一
 * 401 IOT-1001）→ 请求体转换为 CF-7 标准遥测消息（source 服务端定为 IOTDA）→ 经
 * {@link ITelemetryIngestService} 与 AMQP 主链路同一管道落库（绑定快照 + 唯一约束
 * ON CONFLICT DO NOTHING 幂等，双通道同键去重）。
 */
public interface IFallbackIngestService {

    /**
     * 兜底单帧入库编排：鉴权 → CF-7 标准消息转换 → 同管道入库 → info 留痕。
     *
     * <p>幂等语义：与 AMQP 主链路同键（device_id, metric_code, occurred_at）唯一约束去重——
     * 双通道重复推送仅首帧落库（14-iot §3.1），本方法恒正常返回，不区分冲突忽略与实际插入
     * （调用方响应恒 202，inserted 计数仅用于留痕日志）。
     *
     * @param headerToken 兜底鉴权请求头 {@code X-Iot-Fallback-Token} 原文，可空（头未携带为
     *                    null，按鉴权失败处置）；来源：controller 从请求头提取（IoTDA 联动
     *                    规则推送方）
     * @param request     入库请求体（CF-7 同形七字段），非空；已过 controller @Valid 校验；
     *                    来源：IoTDA 联动规则推送载荷
     * @throws BizException 鉴权失败（IOT-1001，401）——请求头缺失、与配置密钥不匹配或服务端
     *                      未配置共享密钥（fail-closed）任一态；由全局渲染器输出 ProblemDetail，
     *                      影响范围仅当前请求，不产生任何落库副作用
     */
    void ingestIotdaFallback(String headerToken, FallbackIngestRequest request);
}
