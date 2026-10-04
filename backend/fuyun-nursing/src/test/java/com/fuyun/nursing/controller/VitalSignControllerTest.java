package com.fuyun.nursing.controller;

import static org.mockito.Mockito.inOrder;

import com.fuyun.nursing.service.IVitalSignService;
import com.fuyun.nursing.service.IWardAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 病区待复核体征清单守卫委托单测（PR-4C Task 6，W-40）：锚定 pendingReview 首行必调
 * {@code assertWardAllowed}（越区在守卫层已 403 NS-1028，本类只锚定委托顺序——守卫先于复核工作台
 * 数据源查询）。
 */
@ExtendWith(MockitoExtension.class)
class VitalSignControllerTest {

    @Mock
    private IWardAccessService wardAccessService;

    @Mock
    private IVitalSignService vitalSignService;

    @InjectMocks
    private VitalSignController controller;

    @Test
    @DisplayName("W-40 守卫委托：待复核体征清单首行校验病区归属后再查（顺序锚定）")
    void pendingReviewDelegatesWardGuardBeforeQuery() {
        controller.pendingReview("W01");
        InOrder order = inOrder(wardAccessService, vitalSignService);
        order.verify(wardAccessService).assertWardAllowed("W01");
        order.verify(vitalSignService).pendingReview("W01");
    }
}
