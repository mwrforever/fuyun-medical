package com.fuyun.common.context;

/**
 * 操作人上下文：审计支撑最小实现，以 ThreadLocal 持有当前请求的操作人标识。
 *
 * <p>业务定位：供 M01 审计切面（PR-3 交付）为 created_by/updated_by 等审计字段注入操作人——
 * 操作人由应用层统一注入（backend 宪法 A.4.2-9），controller/过滤器层写入，持久层切面读取。
 *
 * <p>约束须知：ThreadLocal 不跨线程传播——@Async 与线程池场景不继承操作人（backend 宪法 A.1-10），
 * 异步逻辑必须显式传递操作人标识，防审计断链；请求结束必须调用 {@link #clear()}，防线程复用泄漏。
 */
public final class OperatorContextHolder {

    /** 当前线程的操作人标识；值为操作人业务标识（如员工号），null 表示当前线程无登录上下文 */
    private static final ThreadLocal<String> OPERATOR = new ThreadLocal<>();

    /** 纯静态工具类，禁止实例化 */
    private OperatorContextHolder() {}

    /**
     * 设置当前线程的操作人标识。
     *
     * @param operatorId 操作人业务标识（如员工号/用户 ID），来源：认证通过后解析的登录身份；请求结束前须 clear
     */
    public static void set(String operatorId) {
        OPERATOR.set(operatorId);
    }

    /**
     * 获取当前线程的操作人标识。
     *
     * @return 操作人标识；null 表示当前线程未设置或已清理（如未认证请求）
     */
    public static String get() {
        return OPERATOR.get();
    }

    /**
     * 清理当前线程的操作人标识：请求结束（过滤器/拦截器收尾）必须调用，防止线程复用残留串号。
     */
    public static void clear() {
        OPERATOR.remove();
    }
}
