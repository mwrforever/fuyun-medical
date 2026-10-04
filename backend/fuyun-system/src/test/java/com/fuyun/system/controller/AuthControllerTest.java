package com.fuyun.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.dto.LoginRequest;
import com.fuyun.system.dto.RefreshRequest;
import com.fuyun.system.service.IAuthService;
import com.fuyun.system.vo.BigscreenTokenVO;
import com.fuyun.system.vo.LoginResponse;
import com.fuyun.system.vo.UserVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 认证端点单元测试（controller 编排薄层）：入参透传与响应编排，业务逻辑归 service 层测试。
 *
 * <p>覆盖：login/refresh 请求对象透传与响应直返；logout 剥离 Bearer 前缀后透传令牌原文
 * 并返回 204 无响应体。
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private IAuthService authService;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(authService);
    }

    @Test
    @DisplayName("登录端点：请求对象原样透传服务层，服务响应直返（不做 envelope 包装）")
    void loginDelegatesRequestAndReturnsServiceResponse() {
        LoginRequest request = new LoginRequest("admin", "Fuyun@2026");
        LoginResponse expected = new LoginResponse(
                "access",
                "refresh",
                "Bearer",
                7200L,
                new UserVO(123L, "admin", "系统管理员", null, List.of("ADMIN"), List.of()));
        when(authService.login(request)).thenReturn(expected);

        LoginResponse actual = controller.login(request);

        assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("刷新端点：请求对象原样透传服务层，服务响应直返")
    void refreshDelegatesRequestAndReturnsServiceResponse() {
        RefreshRequest request = new RefreshRequest("refresh-token");
        LoginResponse expected = new LoginResponse(
                "new-access",
                "refresh-token",
                "Bearer",
                7200L,
                new UserVO(123L, "admin", "系统管理员", null, List.of("ADMIN"), List.of()));
        when(authService.refresh(request)).thenReturn(expected);

        LoginResponse actual = controller.refresh(request);

        assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("登出端点：剥离 Bearer 前缀取令牌原文透传，返回 204 无响应体")
    void logoutStripsBearerPrefixAndReturnsNoContent() {
        ResponseEntity<Void> response = controller.logout(SecurityConstants.BEARER_PREFIX + "raw-token-value");

        verify(authService).logout("raw-token-value");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("大屏订阅令牌端点：携 wardId 透传认证服务签发，服务响应直返")
    void bigscreenTokenDelegatesToServiceAndReturnsResponse() {
        BigscreenTokenVO expected = new BigscreenTokenVO("access-token", "Bearer", 300L);
        when(authService.issueBigscreenToken("1001")).thenReturn(expected);

        BigscreenTokenVO actual = controller.bigscreenToken("1001");

        verify(authService).issueBigscreenToken("1001");
        assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("大屏订阅令牌端点：空白 wardId 归一 null（泛哨兵），带首尾空白的 wardId 先 trim 再透传")
    void bigscreenTokenNormalizesBlankWardIdAndTrimsBeforeDelegation() {
        BigscreenTokenVO expected = new BigscreenTokenVO("access-token", "Bearer", 300L);
        // 桩 any()：null 与 trim 后的 "1001" 两次调用均直返同一出参（归一断言由下方 verify 精确承载）
        when(authService.issueBigscreenToken(any())).thenReturn(expected);

        // 空白 wardId 归一 null：泛哨兵会话 wardId 恒 null（空串会过字符串等值误匹配限行防线）
        assertThat(controller.bigscreenToken("   ")).isSameAs(expected);
        verify(authService).issueBigscreenToken(null);
        // 带首尾空白的有效 wardId 先 trim 再透传（query 参数拼接容错）
        assertThat(controller.bigscreenToken(" 1001 ")).isSameAs(expected);
        verify(authService).issueBigscreenToken("1001");
    }
}
