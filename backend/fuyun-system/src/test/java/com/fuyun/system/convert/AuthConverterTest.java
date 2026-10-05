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
    @DisplayName("会话身份转用户出参：身份字段同名映射，employeeId 不出参，permissions 同名映射（PR-4D 填实）")
    void toUserVoMapsSessionIdentity() {
        SessionUser user = new SessionUser(
                123L,
                "admin",
                "系统管理员",
                456L,
                null,
                List.of("ADMIN"),
                null,
                List.of("GET /api/v1/patients", "MENU /dashboard"));

        UserVO vo = converter.toUserVO(user);

        assertThat(vo.userId()).isEqualTo(123L);
        assertThat(vo.loginName()).isEqualTo("admin");
        assertThat(vo.displayName()).isEqualTo("系统管理员");
        assertThat(vo.orgId()).isNull();
        assertThat(vo.roles()).containsExactly("ADMIN");
        // permissions 契约锚定：恒空集表达式已删，经会话身份同名映射携带角色展开的授权点集
        assertThat(vo.permissions()).containsExactly("GET /api/v1/patients", "MENU /dashboard");
    }

    @Test
    @DisplayName("登录响应组装：令牌对与方案名/有效期/用户身份聚合到位")
    void toLoginResponseAggregatesTokenPairAndUser() {
        TokenPair pair = new TokenPair("access-value", "refresh-value");
        UserVO user = new UserVO(123L, "admin", "系统管理员", null, List.of("ADMIN"), List.of());

        LoginResponse response = converter.toLoginResponse(pair, "Bearer", 7200L, user);

        assertThat(response.accessToken()).isEqualTo("access-value");
        assertThat(response.refreshToken()).isEqualTo("refresh-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(7200L);
        assertThat(response.user()).isSameAs(user);
    }
}
