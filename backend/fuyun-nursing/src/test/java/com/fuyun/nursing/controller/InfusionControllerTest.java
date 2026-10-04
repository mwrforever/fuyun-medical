package com.fuyun.nursing.controller;

import static org.mockito.Mockito.inOrder;

import com.fuyun.nursing.service.IInfusionService;
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
 * 病区在途输注清单守卫委托单测（PR-4C Task 6，W-40）：锚定 active 首行必调
 * {@code assertWardAllowed}（越区在守卫层已 403 NS-1028，本类只锚定委托顺序——守卫先于在途聚合查询）。
 */
@ExtendWith(MockitoExtension.class)
class InfusionControllerTest {

    @Mock
    private IWardAccessService wardAccessService;

    @Mock
    private IOrderExecutionOperateService operateService;

    @Mock
    private IInfusionService infusionService;

    @InjectMocks
    private InfusionController controller;

    @Test
    @DisplayName("W-40 守卫委托：在途输注清单首行校验病区归属后再聚合查询（顺序锚定）")
    void activeDelegatesWardGuardBeforeInfusionQuery() {
        controller.active("W01");
        InOrder order = inOrder(wardAccessService, infusionService);
        order.verify(wardAccessService).assertWardAllowed("W01");
        order.verify(infusionService).listActive("W01");
    }
}
