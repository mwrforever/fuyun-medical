package com.fuyun.iot.service.impl;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.FallbackIngestRequest;
import com.fuyun.iot.enums.TelemetrySource;
import com.fuyun.iot.internal.IotFallbackAuthService;
import com.fuyun.iot.service.IFallbackIngestService;
import com.fuyun.iot.service.ITelemetryIngestService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * HTTP 兜底通道入库服务实现（B4.3，BRIEF-PR4-01 §1.5；EX-15 鉴权与消息组装自
 * IotFallbackIngestController 下沉承载，鉴权失败口径/组装字段/错误码语义逐字保持）。
 *
 * <p>执行链：{@link IotFallbackAuthService} 共享密钥常量时间比对鉴权（fail-closed）→
 * 请求体转换为 CF-7 标准消息（source 服务端定为 IOTDA，不开放客户端传入）→ 经
 * {@link ITelemetryIngestService} 与 AMQP 主链路同一入库管道落库（幂等同键去重）→
 * info 留痕。鉴权失败抛 BizException（IOT-1001，401）由全局渲染器（@RestControllerAdvice，
 * 覆盖非 /api/v1 端点）输出 ProblemDetail；落库事务归 TelemetryIngestServiceImpl
 * 方法级（本类不加 @Transactional，与下沉前 controller 形态一致，宪法 A.4.2-7）。
 *
 * <p>无状态单例（依赖只读）；装配归 fuyun-app IotConfig @Import（com.fuyun.iot 不在
 * 组件扫描范围，宪法 B.1）。
 */
@Slf4j
public class FallbackIngestServiceImpl implements IFallbackIngestService {

    /** 兜底鉴权服务：共享密钥常量时间比对执行点（独立于 M01 令牌体系，14-iot §7 R5-09） */
    private final IotFallbackAuthService fallbackAuthService;

    /** 遥测入库服务：与 AMQP 主链路共用的唯一落库通道 */
    private final ITelemetryIngestService ingestService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入既有协作类型）。
     *
     * @param fallbackAuthService 兜底鉴权服务，非空；来源：同模块 internal 装配链
     * @param ingestService       遥测入库服务，非空；来源：同模块 service 装配链
     */
    public FallbackIngestServiceImpl(
            IotFallbackAuthService fallbackAuthService, ITelemetryIngestService ingestService) {
        this.fallbackAuthService = fallbackAuthService;
        this.ingestService = ingestService;
    }

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
    @Override
    public void ingestIotdaFallback(String headerToken, FallbackIngestRequest request) {
        if (!fallbackAuthService.isAuthorized(headerToken)) {
            // 鉴权失败统一 401 IOT-1001（不区分缺失/不匹配/未配置三态，防探测；ProblemDetail 全局渲染）
            throw new BizException(IotErrorCode.FALLBACK_AUTH_FAILED, HttpStatus.UNAUTHORIZED, "兜底通道鉴权失败");
        }
        // 转换为 CF-7 标准消息（source 服务端定为 IOTDA，不开放客户端传入）后走同一入库管道
        StandardTelemetryMessage message = new StandardTelemetryMessage(
                request.deviceId(),
                request.metricCode(),
                request.value(),
                request.unit(),
                request.occurredAt(),
                request.quality(),
                TelemetrySource.IOTDA.getCode());
        int inserted = ingestService.ingest(List.of(message));
        // 兜底通道受理留痕（等保审计口径，B.4-3）：业务标识 + 实际插入行数
        log.info(
                "HTTP 兜底遥测入库完成：deviceId={}，metricCode={}，occurredAt={}，inserted={}",
                request.deviceId(),
                request.metricCode(),
                request.occurredAt(),
                inserted);
    }
}
