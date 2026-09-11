package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.iot.properties.IotProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HTTP 兜底通道鉴权服务单元测试（BRIEF-PR4-01 §4 单测清单：正确/错误/缺失/未配置四态全覆盖）。
 *
 * <p>覆盖：配置密钥 + 正确头放行；配置密钥 + 错误头拒绝；头缺失（null/空串）拒绝；
 * 服务端未配置密钥（null/空串占位）时 fail-closed 一律拒绝——即便请求头撞上空串/任意值。
 * 常量时间比对语义由 MessageDigest.isEqual 承载（时序侧信道防线），此处验证业务四态。
 * 测试 token 均为无意义假值（测试资产，与任何真实凭证无关，红线 6）。
 */
class IotFallbackAuthServiceTest {

    /** 测试资产假密钥（仅具单测意义，与任何真实兜底凭证无关） */
    private static final String TEST_TOKEN = "it-only-fake-fallback-token";

    /** 启用态 AMQP 配置样本（测试资产假值；鉴权服务不消费 amqp 组，仅满足 record 构造） */
    private static final IotProperties.Amqp SAMPLE_AMQP = new IotProperties.Amqp(
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

    @Test
    @DisplayName("正确密钥：请求头与配置值一致放行（true）")
    void authorizesMatchingHeaderToken() {
        IotFallbackAuthService service = serviceWithToken(TEST_TOKEN);

        assertThat(service.isAuthorized(TEST_TOKEN)).isTrue();
    }

    @Test
    @DisplayName("错误密钥：请求头与配置值不一致拒绝（false）")
    void rejectsMismatchingHeaderToken() {
        IotFallbackAuthService service = serviceWithToken(TEST_TOKEN);

        assertThat(service.isAuthorized("totally-wrong-token")).isFalse();
    }

    @Test
    @DisplayName("头缺失：请求头为 null 或空串一律拒绝（false）")
    void rejectsMissingHeaderToken() {
        IotFallbackAuthService service = serviceWithToken(TEST_TOKEN);

        assertThat(service.isAuthorized(null)).as("null 头（头未携带）拒绝").isFalse();
        assertThat(service.isAuthorized("")).as("空串头拒绝").isFalse();
    }

    @Test
    @DisplayName("未配置密钥：服务端 token 为 null 时 fail-closed 一律拒绝（空占位同义）")
    void failsClosedWhenServerTokenUnconfigured() {
        IotFallbackAuthService unconfigured = serviceWithToken(null);

        assertThat(unconfigured.isAuthorized(null)).isFalse();
        assertThat(unconfigured.isAuthorized("")).isFalse();
        assertThat(unconfigured.isAuthorized("any-guessable-value"))
                .as("任意猜测值均拒绝")
                .isFalse();
    }

    @Test
    @DisplayName("未配置密钥（空串占位）：fail-closed 一律拒绝（yml 空占位形态）")
    void failsClosedWhenServerTokenIsBlankPlaceholder() {
        IotFallbackAuthService blankConfigured = serviceWithToken("");

        assertThat(blankConfigured.isAuthorized("")).isFalse();
        assertThat(blankConfigured.isAuthorized(TEST_TOKEN)).as("空配置不因撞值放行").isFalse();
    }

    /** 构造被测服务（record 构造器直建 IotProperties，fallback 组按用例给定） */
    private static IotFallbackAuthService serviceWithToken(String token) {
        return new IotFallbackAuthService(new IotProperties(SAMPLE_AMQP, new IotProperties.Fallback(token)));
    }
}
