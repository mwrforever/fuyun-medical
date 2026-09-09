package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.service.impl.PracticeServiceImpl;
import com.fuyun.system.vo.PracticeCheckResponse;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 执业授权校验骨架服务单元测试（B3.3 交付，BRIEF-PR3-01 §3.3）。
 *
 * <p>覆盖：P0 骨架语义——入参回显（employeeId 字符串化）、checkTime 缺省取当前时刻、
 * 固定语义 passed=false 与占位 reason。真实校验（practice_grant 表 + EFFECTIVE 状态 +
 * 30 天到期通知）随 P1 替换内部实现，响应契约不变。
 */
class PracticeServiceImplTest {

    private PracticeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PracticeServiceImpl();
    }

    @Test
    @DisplayName("check：入参回显 + passed=false 固定语义，checkTime 缺省取服务端当前时刻")
    void checkEchoesRequestWithSkeletonDeniedResultAndDefaultsCheckTime() {
        PracticeCheckResponse response = service.check(new PracticeCheckRequest(123L, "医师执业范围", null));

        assertThat(response.employeeId()).isEqualTo("123");
        assertThat(response.grantType()).isEqualTo("医师执业范围");
        assertThat(response.checkTime()).isNotNull();
        assertThat(response.passed()).isFalse();
        assertThat(response.reason()).isEqualTo("执业授权库表随 P1 交付后启用真实校验");
    }

    @Test
    @DisplayName("check：调用方显式传入 checkTime 时原样回显（查询语义时点）")
    void checkEchoesExplicitCheckTimeWhenProvided() {
        OffsetDateTime explicitTime = OffsetDateTime.parse("2026-09-09T08:00:00+08:00");

        PracticeCheckResponse response = service.check(new PracticeCheckRequest(9L, "手术资质", explicitTime));

        assertThat(response.employeeId()).isEqualTo("9");
        assertThat(response.checkTime()).isEqualTo(explicitTime);
    }
}
