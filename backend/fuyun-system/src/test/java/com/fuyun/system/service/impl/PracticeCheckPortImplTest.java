package com.fuyun.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.system.api.PracticeCheckResult;
import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.service.IPracticeService;
import com.fuyun.system.vo.PracticeCheckResponse;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 执业授权校验端口转调单测（PracticeCheckPort → IPracticeService，Task 8）：passed/reason 原样
 * 透传不二次包装（调用方 OP-1017（M03）/PH-1017（M06）语义不受影响）；入参投影（employeeId/grantType 直传，
 * checkTime 缺省 null=服务端当前时刻口径）。端口转调行覆盖由本测试承载（system impl 包 LINE=1.00）。
 */
@ExtendWith(MockitoExtension.class)
class PracticeCheckPortImplTest {

    @Mock
    private IPracticeService practiceService;

    @Captor
    private ArgumentCaptor<PracticeCheckRequest> requestCaptor;

    @Test
    @DisplayName("校验通过透传：passed=true + reason 原样（不二次包装）")
    void checkDelegatesPassResultWithReason() {
        OffsetDateTime checkTime = OffsetDateTime.now();
        when(practiceService.check(any()))
                .thenReturn(new PracticeCheckResponse("501", "PRESCRIPTION", checkTime, true, "执业授权有效：PRESCRIPTION"));

        PracticeCheckResult result = new PracticeCheckPortImpl(practiceService).check(501L, "PRESCRIPTION");

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isEqualTo("执业授权有效：PRESCRIPTION");
        // 入参直传：employeeId/grantType 逐字、checkTime 缺省 null（服务端当前时刻口径）
        verify(practiceService).check(requestCaptor.capture());
        assertThat(requestCaptor.getValue().employeeId()).isEqualTo(501L);
        assertThat(requestCaptor.getValue().grantType()).isEqualTo("PRESCRIPTION");
        assertThat(requestCaptor.getValue().checkTime()).isNull();
    }

    @Test
    @DisplayName("校验未过透传：passed=false + 两态 reason 原样（消费方拼入 OP-1017/PH-1016 语义）")
    void checkDelegatesFailResultWithReason() {
        when(practiceService.check(any()))
                .thenReturn(new PracticeCheckResponse("502", "PRESCRIPTION", null, false, "无有效执业授权记录：PRESCRIPTION"));

        PracticeCheckResult result = new PracticeCheckPortImpl(practiceService).check(502L, "PRESCRIPTION");

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).isEqualTo("无有效执业授权记录：PRESCRIPTION");
    }
}
