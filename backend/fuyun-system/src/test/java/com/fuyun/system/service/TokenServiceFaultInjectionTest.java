package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.system.properties.SecurityProperties;
import com.fuyun.system.record.SessionData;
import com.fuyun.system.record.SessionUser;
import com.fuyun.system.record.TokenClaims;
import com.fuyun.system.service.impl.TokenServiceImpl;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.crypto.Mac;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 令牌服务系统故障分支故障注入测试（B3.1 审核修复：核心包 JaCoCo LINE=1.00 收口）。
 *
 * <p>覆盖三处"不可恢复系统故障"兜底分支——注入方式按最小侵入原则选取：
 * ①② 经抛 {@code JsonProcessingException} 的 ObjectMapper 桩（mock 方式，序列化器故障不可真实构造）；
 * ③ 经 {@code MockedStatic<Mac>} 注入 JCE 环境异常（HmacSHA256 为 JDK 必备算法，运行期不可达，
 * 仅可故障注入）。断言统一为 fail-closed 契约：整体上抛 IllegalStateException、无半成品令牌/会话泄漏。
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceFaultInjectionTest {

    /** 测试资产假密钥（≥32 字符，仅具单测意义，与任何真实凭证无关；真实密钥只经环境变量注入） */
    private static final String TEST_SECRET = "fault-injection-only-hmac-secret-0123456789ab";

    /** 固定当前时刻：与实现解耦的确定性时间基准 */
    private static final Instant NOW = Instant.parse("2026-09-09T08:00:00Z");

    /** access TTL 测试值 */
    private static final Duration ACCESS_TTL = Duration.ofHours(2);

    /** refresh TTL 测试值 */
    private static final Duration REFRESH_TTL = Duration.ofHours(24);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    /** 故障注入序列化器：按用例需要让会话/载荷序列化抛 JsonProcessingException */
    @Mock
    private ObjectMapper faultMapper;

    private TokenServiceImpl faultService;

    @BeforeEach
    void setUp() {
        // lenient 桩：JCE 故障用例在签名计算阶段即失败，不触达 Redis
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        SecurityProperties properties = new SecurityProperties(TEST_SECRET, ACCESS_TTL, REFRESH_TTL);
        faultService = new TokenServiceImpl(properties, redisTemplate, faultMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("故障注入：会话 JSON 序列化失败时签发整体失败且 Redis 零写入（会话先行的失败侧闭环）")
    void issueFailsClosedWhenSessionSerializationFaults() throws Exception {
        when(faultMapper.writeValueAsString(any(SessionData.class))).thenThrow(jsonFault("故障注入：会话序列化"));

        assertThatThrownBy(() -> faultService.issue(adminUser()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("登录会话序列化失败")
                .hasCauseInstanceOf(JsonProcessingException.class);
        // 序列化先行失败：会话键零写入、令牌未签发——故障侧不得产生"有令牌无会话"的瞬时窗口
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("故障注入：令牌载荷序列化失败时签发整体失败（调用方拿不到半成品令牌对）")
    void issueFailsClosedWhenClaimsSerializationFaults() throws Exception {
        // 会话序列化放行（否则落在上一用例分支），载荷序列化注入故障
        when(faultMapper.writeValueAsString(any(SessionData.class))).thenReturn("{}");
        when(faultMapper.writeValueAsBytes(any(TokenClaims.class))).thenThrow(jsonFault("故障注入：载荷序列化"));

        assertThatThrownBy(() -> faultService.issue(adminUser()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("令牌载荷序列化失败")
                .hasCauseInstanceOf(JsonProcessingException.class);
        // 会话已先行落库，但令牌未签出：整体失败，禁止半成品令牌对外
        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("故障注入：JCE 环境异常时签名计算失败整体上抛且不触达会话（不可达分支兜底契约）")
    void verifyFailsClosedWhenJceEnvironmentFaults() throws Exception {
        try (MockedStatic<Mac> macStatic = mockStatic(Mac.class)) {
            // 注入 NoSuchAlgorithmException（getInstance 的声明受检异常，属 GeneralSecurityException 子类）
            macStatic.when(() -> Mac.getInstance("HmacSHA256")).thenThrow(new NoSuchAlgorithmException("故障注入：JCE 环境"));

            // 令牌内容无关紧要：签名计算在校验链最前端失败（两段格式合法即可到达）
            assertThatThrownBy(() -> faultService.verify("aaaa.bb", "access"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HMAC-SHA256 签名计算失败")
                    .hasCauseInstanceOf(GeneralSecurityException.class);
        }
        // 签名计算先于会话读取失败：Redis 不得被触达
        verify(valueOps, never()).get(anyString());
    }

    /**
     * JsonProcessingException 实例工厂：取标准具体子类 JsonMappingException（公共构造器，可脱离真实
     * 序列化器直建——抽象基类 JsonProcessingException 构造器为 protected 不可直建）。
     *
     * @param message 故障说明（测试可读性，非业务断言依据）
     * @return JsonProcessingException 实例，非空
     */
    private static JsonMappingException jsonFault(String message) {
        return new JsonMappingException(null, message);
    }

    /** 登录会话输入夹具：字段值与主测试类口径一致 */
    private static SessionUser adminUser() {
        return new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"));
    }
}
