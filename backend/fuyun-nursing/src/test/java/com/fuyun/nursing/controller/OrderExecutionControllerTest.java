package com.fuyun.nursing.controller;

import static org.mockito.Mockito.inOrder;

import com.fuyun.nursing.service.IOrderExecutionOperateService;
import com.fuyun.nursing.service.IWardAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 执行工作台清单守卫委托单测（PR-4C Task 6，W-40）：锚定 list 首行必调
 * {@code assertWardAllowed}（越区在守卫层已 403 NS-1028，本类只锚定委托顺序——守卫先于工作台
 * 分组清单查询；可选过滤全空走最小直调）。
 */
@ExtendWith(MockitoExtension.class)
class OrderExecutionControllerTest {

    @Mock
    private IWardAccessService wardAccessService;

    @Mock
    private IOrderExecutionOperateService operateService;

    @InjectMocks
    private OrderExecutionController controller;

    @Test
    @DisplayName("W-40 守卫委托：执行工作台清单首行校验病区归属后再分组查询（顺序锚定）")
    void listDelegatesWardGuardBeforeWorkbenchQuery() {
        controller.list("W01", null, null, null, 0, 20);
        InOrder order = inOrder(wardAccessService, operateService);
        order.verify(wardAccessService).assertWardAllowed("W01");
        order.verify(operateService).listWorkbench("W01", null, null, null, 0, 20);
    }
}
