package com.fuyun.nursing.controller;

import static org.mockito.Mockito.inOrder;

import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.service.IRoutineTaskGenerator;
import com.fuyun.nursing.service.IWardAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 护理任务清单守卫委托单测（PR-4C Task 6，W-40）：锚定 list 首行必调
 * {@code assertWardAllowed}（越区在守卫层已 403 NS-1028，本类只锚定委托顺序——守卫先于清单查询；
 * status/date 可空路径走最小直调）。
 */
@ExtendWith(MockitoExtension.class)
class NursingTaskControllerTest {

    @Mock
    private IWardAccessService wardAccessService;

    @Mock
    private INursingTaskService taskService;

    @Mock
    private IRoutineTaskGenerator routineTaskGenerator;

    @InjectMocks
    private NursingTaskController controller;

    @Test
    @DisplayName("W-40 守卫委托：任务清单首行校验病区归属后再查病区任务（顺序锚定）")
    void listDelegatesWardGuardBeforeTaskQuery() {
        controller.list("W01", null, null);
        InOrder order = inOrder(wardAccessService, taskService);
        order.verify(wardAccessService).assertWardAllowed("W01");
        order.verify(taskService).list("W01", null, null);
    }
}
