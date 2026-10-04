package com.fuyun.nursing.controller;

import static org.mockito.Mockito.inOrder;

import com.fuyun.nursing.service.IWardAccessService;
import com.fuyun.nursing.service.IWardMetaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 病区元数据读面守卫委托单测（PR-4C Task 6，W-40）：锚定 listByWard 首行必调
 * {@code assertWardAllowed}（越区在守卫层已 403 NS-1028，本类只锚定委托顺序——守卫先于一览查询）。
 */
@ExtendWith(MockitoExtension.class)
class WardControllerTest {

    @Mock
    private IWardAccessService wardAccessService;

    @Mock
    private IWardMetaService wardMetaService;

    @InjectMocks
    private WardController controller;

    @Test
    @DisplayName("W-40 守卫委托：listByWard 首行校验病区归属后再查在区患者一览（顺序锚定）")
    void listByWardDelegatesWardGuardBeforePatientQuery() {
        controller.listByWard("W01");
        InOrder order = inOrder(wardAccessService, wardMetaService);
        order.verify(wardAccessService).assertWardAllowed("W01");
        order.verify(wardMetaService).listByWard("W01");
    }
}
