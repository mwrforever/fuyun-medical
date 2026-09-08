package com.fuyun.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 业务异常基座单元测试：验证错误码、HTTP 状态与消息三类信息在两种构造路径下的正确携带。
 */
class BizExceptionTest {

    /** 测试专用错误码枚举：模拟各业务模块 api/ 包下的真实错误码枚举实现 */
    private enum TestErrorCode implements ErrorCode {
        ORDER_NOT_PAYABLE("ORDE-1001");

        private final String code;

        TestErrorCode(String code) {
            this.code = code;
        }

        @Override
        public String getCode() {
            return code;
        }
    }

    @Test
    @DisplayName("全参构造器携带正确的 errorCode、httpStatus 与自定义 message")
    void holdsErrorCodeStatusAndMessageWithFullConstructor() {
        BizException exception = new BizException(TestErrorCode.ORDER_NOT_PAYABLE, HttpStatus.CONFLICT, "订单当前状态不可支付");

        assertThat(exception.getErrorCode().getCode()).isEqualTo("ORDE-1001");
        assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getMessage()).isEqualTo("订单当前状态不可支付");
    }

    @Test
    @DisplayName("便捷构造器未显式给 message 时默认取错误码自身")
    void defaultsMessageToErrorCodeWithConvenienceConstructor() {
        BizException exception = new BizException(TestErrorCode.ORDER_NOT_PAYABLE, HttpStatus.CONFLICT);

        assertThat(exception.getErrorCode().getCode()).isEqualTo("ORDE-1001");
        assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getMessage()).isEqualTo("ORDE-1001");
    }
}
