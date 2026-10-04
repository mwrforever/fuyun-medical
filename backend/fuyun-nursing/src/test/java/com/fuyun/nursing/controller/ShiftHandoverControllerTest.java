package com.fuyun.nursing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;

import com.fuyun.nursing.service.IShiftHandoverService;
import com.fuyun.nursing.service.IWardAccessService;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 交接班清单守卫委托单测（PR-4C Task 6，W-40）：锚定 list 首行必调
 * {@code assertWardAllowed}（越区在守卫层已 403 NS-1028，本类只锚定委托顺序——守卫先于按日检索；
 * date 缺省北京当日由 controller 内部补值，verify 以 any 承载）。
 */
@ExtendWith(MockitoExtension.class)
class ShiftHandoverControllerTest {

    @Mock
    private IWardAccessService wardAccessService;

    @Mock
    private IShiftHandoverService handoverService;

    @InjectMocks
    private ShiftHandoverController controller;

    @Test
    @DisplayName("W-40 守卫委托：交接班清单首行校验病区归属后再按日检索（顺序锚定）")
    void listDelegatesWardGuardBeforeHandoverQuery() {
        controller.list("W01", null);
        InOrder order = inOrder(wardAccessService, handoverService);
        order.verify(wardAccessService).assertWardAllowed("W01");
        order.verify(handoverService).listByWard(eq("W01"), any(LocalDate.class));
    }
}
