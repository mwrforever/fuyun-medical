package com.fuyun.system.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.system.record.SessionUser;
import com.fuyun.system.record.TokenPair;
import com.fuyun.system.vo.LoginResponse;
import com.fuyun.system.vo.UserVO;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 认证域 MapStruct 转换器测试（映射契约：字段同名映射与聚合组装，A.7-4 关键映射单测）。
 */
class AuthConverterTest {

    /** 转换器实例：与 SystemWebConfig 装配同源（Mappers.getMapper 取生成实现） */
    private final AuthConverter converter = AuthConverter.INSTANCE;

    @Test
    @DisplayName("会话身份转用户出参：身份字段同名映射，employeeId 不出参（内部标识防外泄）")
    void toUserVoMapsSessionIdentity() {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"));

        UserVO vo = converter.toUserVO(user);

        assertThat(vo.userId()).isEqualTo(123L);
        assertThat(vo.loginName()).isEqualTo("admin");
        assertThat(vo.displayName()).isEqualTo("系统管理员");
        assertThat(vo.orgId()).isNull();
        assertThat(vo.roles()).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("登录响应组装：令牌对与方案名/有效期/用户身份聚合到位")
    void toLoginResponseAggregatesTokenPairAndUser() {
        TokenPair pair = new TokenPair("access-value", "refresh-value");
        UserVO user = new UserVO(123L, "admin", "系统管理员", null, List.of("ADMIN"));

        LoginResponse response = converter.toLoginResponse(pair, "Bearer", 7200L, user);

        assertThat(response.accessToken()).isEqualTo("access-value");
        assertThat(response.refreshToken()).isEqualTo("refresh-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(7200L);
        assertThat(response.user()).isSameAs(user);
    }
}
