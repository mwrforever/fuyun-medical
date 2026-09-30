package com.fuyun.iot.controller;

import com.fuyun.iot.dto.FallbackIngestRequest;
import com.fuyun.iot.service.IFallbackIngestService;
import com.fuyun.iot.vo.FallbackIngestResponse;
import jakarta.validation.Valid;
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
 * 天然不拦截，独立鉴权自建（共享密钥常量时间比对，判定点在
 * {@link IFallbackIngestService} 编排链内）；鉴权失败 401 IOT-1001 由全局渲染器
 * ProblemDetail 自动生效（GlobalExceptionHandler 为全局 @RestControllerAdvice，
 * 覆盖非 /api/v1 控制器）；载荷校验失败 400 ProblemDetail（@Valid）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用兜底入库服务 + 编排 202 响应，
 * 禁业务逻辑、禁 @Transactional（鉴权判定/CF-7 消息组装/落库调用/留痕日志均下沉
 * {@link IFallbackIngestService}，EX-15）；装配归 fuyun-app IotConfig @Import
 * （com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 */
@RestController
@RequestMapping("/ingest")
public class IotFallbackIngestController {

    /** 兜底鉴权请求头（简报 §1.5 原文头名；常量收口防散落） */
    static final String FALLBACK_TOKEN_HEADER = "X-Iot-Fallback-Token";

    /** 兜底入库服务：鉴权判定 + CF-7 消息转换 + 同管道落库的编排单点（EX-15 下沉承载） */
    private final IFallbackIngestService fallbackIngestService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param fallbackIngestService 兜底入库服务，非空
     */
    public IotFallbackIngestController(IFallbackIngestService fallbackIngestService) {
        this.fallbackIngestService = fallbackIngestService;
    }

    /**
     * 兜底单帧入库：@Valid 校验 → 委派兜底入库服务（鉴权 + CF-7 转换 + 同管道入库）→ 202。
     *
     * <p>幂等语义：与 AMQP 主链路同键（device_id, metric_code, occurred_at）唯一约束去重——
     * 双通道重复推送仅首帧落库（14-iot §3.1），响应恒 202 不区分冲突忽略与实际插入。
     *
     * @param headerToken 兜底鉴权请求头，可空（缺失按鉴权失败处置）；来源：IoTDA 联动规则推送方
     * @param request     入库请求体（CF-7 同形），非空；@Valid 校验失败由全局渲染器输出 400
     * @return 202 + {"accepted": true}
     * @throws com.fuyun.common.exception.BizException 鉴权失败（IOT-1001，401）——头缺失、
     *                       与配置密钥不匹配或服务端未配置（由兜底入库服务抛出，全局渲染
     *                       ProblemDetail）
     */
    @PostMapping("/iotda-fallback")
    public ResponseEntity<FallbackIngestResponse> ingestIotdaFallback(
            @RequestHeader(value = FALLBACK_TOKEN_HEADER, required = false) String headerToken,
            @Valid @RequestBody FallbackIngestRequest request) {
        fallbackIngestService.ingestIotdaFallback(headerToken, request);
        return ResponseEntity.accepted().body(new FallbackIngestResponse(true));
    }
}
