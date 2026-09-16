package com.fuyun.common.context;

import java.util.List;

/**
 * 角色上下文：以 ThreadLocal 持有当前请求的角色编码清单（脱敏豁免角色判定与 P1 鉴权拦截的统一来源）。
 *
 * <p>与 {@link OperatorContextHolder} 同构同契约：不跨线程传播（@Async 场景显式传递，A.1-10）、
 * 请求结束必须 clear（AuthTokenInterceptor afterCompletion 统一清理）。变更属 M01/common 交界
 * 契约，PR 描述申报（PR-2 消费方：PrivacyMaskService 豁免判定）。
 */
public final class RoleContextHolder {

    /** 当前线程角色编码清单；null 表示未设置（get 回退空清单，永不为 null） */
    private static final ThreadLocal<List<String>> ROLES = new ThreadLocal<>();

    /** 纯静态工具类，禁止实例化 */
    private RoleContextHolder() {}

    /**
     * 设置当前线程角色清单。
     *
     * @param roles 角色编码清单（如 ["ADMIN","DOCTOR"]），null 按空清单落（SessionData.roles 契约非 null）；请求结束前须 clear
     */
    public static void set(List<String> roles) {
        ROLES.set(roles == null ? List.of() : roles);
    }

    /**
     * 取当前线程角色清单。
     *
     * @return 角色编码清单；未设置/已清理时为空清单（永不为 null）
     */
    public static List<String> get() {
        List<String> roles = ROLES.get();
        return roles == null ? List.of() : roles;
    }

    /** 清理当前线程角色清单（请求收尾必须调用，防线程复用残留） */
    public static void clear() {
        ROLES.remove();
    }
}
