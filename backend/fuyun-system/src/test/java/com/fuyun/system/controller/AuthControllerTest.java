package com.fuyun.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.dto.LoginRequest;
import com.fuyun.system.dto.RefreshRequest;
import com.fuyun.system.service.IAuthService;
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
                "access", "refresh", "Bearer", 7200L, new UserVO(123L, "admin", "系统管理员", null, List.of("ADMIN")));
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
                new UserVO(123L, "admin", "系统管理员", null, List.of("ADMIN")));
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
}
