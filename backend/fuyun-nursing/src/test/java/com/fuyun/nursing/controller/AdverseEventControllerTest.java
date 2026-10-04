package com.fuyun.nursing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.nursing.service.IAdverseEventService;
import com.fuyun.nursing.service.IWardAccessService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 不良事件查询/统计守卫与绑定集过滤委托单测（PR-4C Task 6，W-40）：两条 scope 传递路径锚定——
 * 携 wardId 时校验归属且 service 传 null scope（单病区视角，越区在守卫层已 403）；不携 wardId 时
 * 按操作者当班绑定集过滤（单病区绑定=默认本病区视角，页面行为不变）。操作者取 ThreadLocal 上下文。
 */
@ExtendWith(MockitoExtension.class)
class AdverseEventControllerTest {

    /** 测试操作者（登录令牌身份——ThreadLocal 直传绑定集查询） */
    private static final String OPERATOR = "1001";

    @Mock
    private IWardAccessService wardAccessService;

    @Mock
    private IAdverseEventService adverseEventService;

    @InjectMocks
    private AdverseEventController controller;

    @BeforeEach
    void setUp() {
        OperatorContextHolder.set(OPERATOR);
    }

    @AfterEach
    void clearContext() {
        // 防御性清理：防操作者上下文串号泄漏到其他用例
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("W-40 携 wardId：校验归属后 service 传 null scope（单病区视角，不取绑定集）")
    void listAndStatsWithWardIdAssertBelongingAndPassNullScope() {
        controller.list("FALL", "W01", "REPORTED", null, 0, 20);
        controller.stats("FALL", "W01", null);

        InOrder order = inOrder(wardAccessService, adverseEventService);
        order.verify(wardAccessService).assertWardAllowed("W01");
        order.verify(adverseEventService).list("FALL", "W01", "REPORTED", null, 0, 20, null);
        order.verify(wardAccessService).assertWardAllowed("W01");
        order.verify(adverseEventService).stats("FALL", "W01", null, null);
        // 携 wardId 面不取绑定集（归属校验由守卫单点承载）
        verify(wardAccessService, never()).activeBoundWardIds(any());
    }

    @Test
    @DisplayName("W-40 不携 wardId：按当班绑定集过滤（scope 透传 service，不做归属断言）")
    void listAndStatsWithoutWardIdFilterByActiveBoundScope() {
        when(wardAccessService.activeBoundWardIds(OPERATOR)).thenReturn(List.of("W01"));

        controller.list(null, null, null, null, 0, 20);
        controller.stats(null, null, null);

        InOrder order = inOrder(adverseEventService);
        order.verify(adverseEventService).list(null, null, null, null, 0, 20, List.of("W01"));
        order.verify(adverseEventService).stats(null, null, null, List.of("W01"));
        // list/stats 各取一次绑定集（操作者=ThreadLocal 令牌身份）
        verify(wardAccessService, times(2)).activeBoundWardIds(OPERATOR);
        verify(wardAccessService, never()).assertWardAllowed(any());
    }
}
