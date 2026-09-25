package com.fuyun.billing.internal;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fuyun.billing.service.IInpatientChargeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 床位费日切定时任务单测（internal/ 不在 service.impl 门禁包，在院判定与幂等兜底逻辑归
 * InpatientChargeServiceImplTest 承载——本测试锚定调度触发面：单一委托调用，任务无自有业务逻辑）。
 */
@ExtendWith(MockitoExtension.class)
class InpatientDailyChargeJobTest {

    /** 床位费日切业务面（在院扫描/逐行计价/幂等跳过全归服务层） */
    @Mock
    private IInpatientChargeService inpatientChargeService;

    private InpatientDailyChargeJob job;

    @BeforeEach
    void setUp() {
        job = new InpatientDailyChargeJob(inpatientChargeService);
    }

    @Test
    @DisplayName("日切触发：单一委托 dailyBedCharge（02:30 全院在院床位费，任务无自有业务逻辑）")
    void chargeDailyBedFeeDelegatesToService() {
        when(inpatientChargeService.dailyBedCharge()).thenReturn(42);

        job.chargeDailyBedFee();

        // 单一委托调用：任务类禁业务逻辑（A.1-8 同源口径——扫描/幂等/计价全归服务层）
        verify(inpatientChargeService).dailyBedCharge();
        verifyNoMoreInteractions(inpatientChargeService);
    }
}
