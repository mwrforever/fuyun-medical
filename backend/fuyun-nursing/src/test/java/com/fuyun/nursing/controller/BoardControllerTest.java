package com.fuyun.nursing.controller;

import static org.mockito.Mockito.inOrder;

import com.fuyun.nursing.service.INurseBoardService;
import com.fuyun.nursing.service.IWardAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 大屏快照端点守卫委托单测（PR-4C Task 6，W-40）：锚定 board 首行必调
 * {@code assertWardAllowed}（越区在守卫层已 403 NS-1028，本类只锚定委托顺序——守卫先于快照查询；
 * 哨兵豁免归守卫内部逻辑，controller 层不重复测试）。
 */
@ExtendWith(MockitoExtension.class)
class BoardControllerTest {

    @Mock
    private IWardAccessService wardAccessService;

    @Mock
    private INurseBoardService boardService;

    @InjectMocks
    private BoardController controller;

    @Test
    @DisplayName("W-40 守卫委托：board 首行校验病区归属后再取快照（顺序锚定）")
    void boardDelegatesWardGuardBeforeSnapshot() {
        controller.board("W01");
        InOrder order = inOrder(wardAccessService, boardService);
        order.verify(wardAccessService).assertWardAllowed("W01");
        order.verify(boardService).board("W01");
    }
}
