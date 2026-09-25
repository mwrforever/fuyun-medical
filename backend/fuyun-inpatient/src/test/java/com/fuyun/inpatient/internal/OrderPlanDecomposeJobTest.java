package com.fuyun.inpatient.internal;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fuyun.inpatient.service.OrderPlanService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 日切分解定时任务单测（internal/ 不在 service.impl 门禁包，分批事务边界与幂等兜底逻辑归
 * OrderPlanServiceImplTest 承载——本测试锚定调度触发面：次日计划日期入参与单一委托调用）。
 */
@ExtendWith(MockitoExtension.class)
class OrderPlanDecomposeJobTest {

    /** 日切批量分解业务面（候选查询/分批事务/幂等兜底全归服务层） */
    @Mock
    private OrderPlanService orderPlanService;

    private OrderPlanDecomposeJob job;

    @BeforeEach
    void setUp() {
        job = new OrderPlanDecomposeJob(orderPlanService);
    }

    @Test
    @DisplayName("日切触发：以次日为计划日期单一委托批量分解（凌晨 02:00 生成次日计划，任务无自有业务逻辑）")
    void decomposeDelegatesToServiceWithNextDayPlanDate() {
        LocalDate expected = LocalDate.now().plusDays(1);
        when(orderPlanService.decomposeNextDay(expected)).thenReturn(42);

        job.decompose();

        // 单一委托调用：任务类禁业务逻辑（A.1-8 同源口径——分批/幂等/候选全归服务层）
        verify(orderPlanService).decomposeNextDay(expected);
        verifyNoMoreInteractions(orderPlanService);
    }
}
