package com.fuyun.iot.controller;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.FallbackIngestRequest;
import com.fuyun.iot.enums.TelemetrySource;
import com.fuyun.iot.internal.IotFallbackAuthService;
import com.fuyun.iot.service.ITelemetryIngestService;
import com.fuyun.iot.vo.FallbackIngestResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 兜底入库端点（POST /ingest/iotda-fallback，BRIEF-PR4-01 §1.5 路径原样）。
 *
 * <p>定位：AMQP 主链路（IoTDA 规则引擎转发消费）的 HTTP 兜底通道（宪法 A.5-10"兜底通道保留"），
 * 载荷 CF-7 同形 JSON 走与 AMQP 同一入库管道（绑定快照 + 唯一约束 ON CONFLICT DO NOTHING
 * 幂等，双通道同键去重）。<b>路径不在 /api/v1 前缀下</b>：system 认证拦截器（挂 /api/v1/**）
 * 天然不拦截，独立鉴权自建（{@link IotFallbackAuthService} 共享密钥常量时间比对）；鉴权失败
 * 401 IOT-1001 由全局渲染器 ProblemDetail 自动生效（GlobalExceptionHandler 为全局
 * @RestControllerAdvice，覆盖非 /api/v1 控制器）；载荷校验失败 400 ProblemDetail（@Valid）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 鉴权 + 转换为标准遥测消息 + 调用入库服务 +
 * 编排响应，禁业务逻辑、禁 @Transactional（入库事务归 service impl 方法级）。
 * 装配归 fuyun-app IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 */
@Slf4j
@RestController
@RequestMapping("/ingest")
public class IotFallbackIngestController {

    /** 兜底鉴权请求头（简报 §1.5 原文头名；常量收口防散落） */
    static final String FALLBACK_TOKEN_HEADER = "X-Iot-Fallback-Token";

    /** 兜底通道独立鉴权服务：共享密钥常量时间比对执行点 */
    private final IotFallbackAuthService fallbackAuthService;

    /** 遥测入库服务：与 AMQP 主链路共用的唯一落库通道 */
    private final ITelemetryIngestService ingestService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param fallbackAuthService 兜底鉴权服务，非空
     * @param ingestService       遥测入库服务，非空
     */
    public IotFallbackIngestController(
            IotFallbackAuthService fallbackAuthService, ITelemetryIngestService ingestService) {
        this.fallbackAuthService = fallbackAuthService;
        this.ingestService = ingestService;
    }

    /**
     * 兜底单帧入库：鉴权 → CF-7 标准消息转换（source 定为 IOTDA）→ 同管道入库 → 202。
     *
     * <p>幂等语义：与 AMQP 主链路同键（device_id, metric_code, occurred_at）唯一约束去重——
     * 双通道重复推送仅首帧落库（14-iot §3.1），响应恒 202 不区分冲突忽略与实际插入。
     *
     * @param headerToken 兜底鉴权请求头，可空（缺失按鉴权失败处置）；来源：IoTDA 联动规则推送方
     * @param request     入库请求体（CF-7 同形），非空；@Valid 校验失败由全局渲染器输出 400
     * @return 202 + {"accepted": true}
     * @throws BizException 鉴权失败（IOT-1001，401）——头缺失、与配置密钥不匹配或服务端未配置
     */
    @PostMapping("/iotda-fallback")
    public ResponseEntity<FallbackIngestResponse> ingestIotdaFallback(
            @RequestHeader(value = FALLBACK_TOKEN_HEADER, required = false) String headerToken,
            @Valid @RequestBody FallbackIngestRequest request) {
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
        log.info(
                "HTTP 兜底遥测入库完成：deviceId={}，metricCode={}，occurredAt={}，inserted={}",
                request.deviceId(),
                request.metricCode(),
                request.occurredAt(),
                inserted);
        return ResponseEntity.accepted().body(new FallbackIngestResponse(true));
    }
}
