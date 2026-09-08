package com.fuyun.common.exception;

/**
 * 错误码契约接口：统一全项目业务错误码的取值形态。
 *
 * <p>约定格式 {@code <模块助记>-<4位数字>}（如 {@code ORDR-1001}，backend 宪法 A.3-4）；
 * 实现类为各业务模块 api/ 包下的错误码枚举，全项目唯一，禁止跨模块复用同一编码。
 * 本模块只定义契约，不含任何业务语义。
 */
public interface ErrorCode {

    /**
     * 取业务错误码。
     *
     * @return 错误码字符串，格式 {@code <模块助记>-<4位数字>}，非空；经全局异常渲染输出至 ProblemDetail.properties.errorCode
     */
    String getCode();
}
