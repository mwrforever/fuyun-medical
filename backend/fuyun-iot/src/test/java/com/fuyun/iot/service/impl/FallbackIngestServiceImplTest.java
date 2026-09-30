package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.FallbackIngestRequest;
import com.fuyun.iot.enums.TelemetrySource;
import com.fuyun.iot.internal.IotFallbackAuthService;
import com.fuyun.iot.properties.IotProperties;
import com.fuyun.iot.service.ITelemetryIngestService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 兜底入库服务单元测试（EX-15 下沉承载面：鉴权判定 + CF-7 消息组装 + 同管道入库调用）。
 *
 * <p>鉴权判定走真实 {@link IotFallbackAuthService}（常量时间比对三态语义由
 * IotFallbackAuthServiceTest 全量承载，此处覆盖编排链贯通态）；入库服务 mock，经参数
 * 捕获断言 CF-7 消息七字段逐字口径（source 恒 IOTDA、quality 缺省补 GOOD）。
 * 鉴权失败口径锁定：IOT-1001 + 401 + 消息原文「兜底通道鉴权失败」+ ingest 零调用。
 * 测试 token 均为无意义假值（测试资产，与任何真实凭证无关，红线 6）。
 */
@ExtendWith(MockitoExtension.class)
class FallbackIngestServiceImplTest {

    /** 测试资产假密钥（仅具单测意义，与任何真实兜底凭证无关） */
    private static final String TEST_TOKEN = "unit-only-fake-fallback-token";

    /** 样本请求七字段（quality 显式 SUSPECT，验证透传；缺省链路另用例覆盖） */
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-28T08:30:00Z");

    @Mock
    private ITelemetryIngestService ingestService;

    /** 被测服务：真实鉴权服务（已配置 TEST_TOKEN）+ mock 入库服务 */
    private FallbackIngestServiceImpl fallbackIngestService;

    @BeforeEach
    void setUp() {
        fallbackIngestService = new FallbackIngestServiceImpl(
                new IotFallbackAuthService(new IotProperties(sampleAmqp(), new IotProperties.Fallback(TEST_TOKEN))),
                ingestService);
    }

    @Test
    @DisplayName("正常路径：密钥匹配 → ingest 以 CF-7 标准消息（source=IOTDA、quality 透传）被调用且正常返回")
    void ingestsWithStandardMessageWhenAuthorized() {
        FallbackIngestRequest request = sampleRequest("SUSPECT");

        assertThatCode(() -> fallbackIngestService.ingestIotdaFallback(TEST_TOKEN, request))
                .doesNotThrowAnyException();

        // 捕获落库批次，断言 CF-7 七字段逐字口径（source 服务端定为 IOTDA，不开放客户端传入）
        ArgumentCaptor<List<StandardTelemetryMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(ingestService).ingest(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        StandardTelemetryMessage message = captor.getValue().get(0);
        assertThat(message.deviceId()).isEqualTo("dev-fallback-001");
        assertThat(message.metricCode()).isEqualTo("MDC_ECG_HEART_RATE");
        assertThat(message.value()).isEqualTo("72");
        assertThat(message.unit()).isEqualTo("bpm");
        assertThat(message.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(message.quality()).isEqualTo("SUSPECT");
        assertThat(message.source()).isEqualTo(TelemetrySource.IOTDA.getCode());
    }

    @Test
    @DisplayName("quality 缺省：请求未携带 quality（DTO 补 GOOD）→ 组装消息 quality=GOOD")
    void ingestsWithDefaultGoodQualityWhenAbsent() {
        FallbackIngestRequest request = sampleRequest(null);

        fallbackIngestService.ingestIotdaFallback(TEST_TOKEN, request);

        ArgumentCaptor<List<StandardTelemetryMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(ingestService).ingest(captor.capture());
        assertThat(captor.getValue().get(0).quality())
                .as("quality 缺省经 DTO 紧凑构造器补 GOOD 后原样透传")
                .isEqualTo("GOOD");
    }

    @Test
    @DisplayName("密钥不匹配：BizException 口径 IOT-1001/401/消息原文，ingest 零调用")
    void throwsAuthFailedOnMismatchingToken() {
        assertThatThrownBy(
                        () -> fallbackIngestService.ingestIotdaFallback("totally-wrong-token", sampleRequest("GOOD")))
                .isInstanceOf(BizException.class)
                .hasMessage("兜底通道鉴权失败")
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getErrorCode()).isEqualTo(IotErrorCode.FALLBACK_AUTH_FAILED);
                    assertThat(biz.getErrorCode().getCode()).isEqualTo("IOT-1001");
                    assertThat(biz.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
        verifyNoInteractions(ingestService);
    }

    @Test
    @DisplayName("头缺失：headerToken 为 null → 同口径 401 IOT-1001，ingest 零调用")
    void throwsAuthFailedOnMissingHeaderToken() {
        assertThatThrownBy(() -> fallbackIngestService.ingestIotdaFallback(null, sampleRequest("GOOD")))
                .isInstanceOf(BizException.class)
                .hasMessage("兜底通道鉴权失败")
                .satisfies(ex -> {
                    assertThat(((BizException) ex).getErrorCode()).isEqualTo(IotErrorCode.FALLBACK_AUTH_FAILED);
                    assertThat(((BizException) ex).getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
        verifyNoInteractions(ingestService);
    }

    @Test
    @DisplayName("未配置密钥：服务端 fail-closed 一律拒绝（即便撞上任意头值），ingest 零调用")
    void failsClosedWhenServerTokenUnconfigured() {
        FallbackIngestServiceImpl unconfigured = new FallbackIngestServiceImpl(
                new IotFallbackAuthService(new IotProperties(sampleAmqp(), new IotProperties.Fallback(null))),
                ingestService);

        assertThatThrownBy(() -> unconfigured.ingestIotdaFallback("any-guessable-value", sampleRequest("GOOD")))
                .isInstanceOf(BizException.class)
                .hasMessage("兜底通道鉴权失败")
                .satisfies(ex -> {
                    assertThat(((BizException) ex).getErrorCode()).isEqualTo(IotErrorCode.FALLBACK_AUTH_FAILED);
                    assertThat(((BizException) ex).getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
        verifyNoInteractions(ingestService);
    }

    /**
     * 样本请求体（CF-7 同形）：quality 按用例给定（null 走 DTO 紧凑构造器补 GOOD）。
     */
    private static FallbackIngestRequest sampleRequest(String quality) {
        return new FallbackIngestRequest("dev-fallback-001", "MDC_ECG_HEART_RATE", "72", "bpm", OCCURRED_AT, quality);
    }

    /** 禁用态 AMQP 配置样本（测试资产假值；鉴权服务不消费 amqp 组，仅满足 record 构造） */
    private static IotProperties.Amqp sampleAmqp() {
        return new IotProperties.Amqp(
                false,
                null,
                null,
                null,
                null,
                1000,
                500,
                Duration.ofSeconds(2),
                5000,
                Duration.ofSeconds(3),
                Duration.ofSeconds(30));
    }
}
