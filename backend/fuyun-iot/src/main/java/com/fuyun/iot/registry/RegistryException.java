package com.fuyun.iot.registry;

/**
 * 注册中心不可用异常（P2 PR-2 Task 4）：IotDeviceRegistry 全部实现方法对 SDK/网络/云端拒绝
 * 异常的统一翻译出口，固定承载 IOT-1022 REGISTRY_UNAVAILABLE 语义（503）——接口契约层面
 * 收敛异常面，调用方无需逐个感知华为 SDK 异常类型。
 *
 * <p>处理策略：调用方（服务层）捕获后转 {@code BizException(IotErrorCode.REGISTRY_UNAVAILABLE,
 * HttpStatus.SERVICE_UNAVAILABLE, message)} 由全局渲染器出 RFC 9457 ProblemDetail；实现层抛出
 * 前必须先落 error 日志（含云端错误码/摘要，禁凭证明文）。
 */
public class RegistryException extends RuntimeException {

    /**
     * 构造不可用异常（无底层原因，云端明确拒绝场景）。
     *
     * @param message 中文失败摘要（含操作名与业务标识，禁凭证明文），非空
     */
    public RegistryException(String message) {
        super(message);
    }

    /**
     * 构造不可用异常（含底层原因，SDK/网络异常场景）。
     *
     * @param message 中文失败摘要（含操作名与业务标识，禁凭证明文），非空
     * @param cause   底层异常（SDK 异常原样挂链保堆栈），可空
     */
    public RegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
