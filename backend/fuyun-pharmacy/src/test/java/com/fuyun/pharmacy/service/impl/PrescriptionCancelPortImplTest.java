package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.service.IPrescriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 处方作废端口单测（跨模块 PortImpl 转调件——pharmacy service.impl 包 LINE=1.00 行覆盖承载）：
 * rxNo/reason 转调既有 cancel 主链（未缴费作废语义不变），异常原样上抛不吞（已缴费拒 PH-1014
 * 引导退费链口径不变）。
 */
@ExtendWith(MockitoExtension.class)
class PrescriptionCancelPortImplTest {

    @Mock
    private IPrescriptionService prescriptionService;

    @Test
    @DisplayName("作废端口转调：rxNo/reason 透传既有 cancel，PH-1014 业务拒绝原样上抛（转调不吞，引导退费链语义不变）")
    void cancelDelegatesToExistingCancelAndPropagatesRejection() {
        PrescriptionCancelPortImpl impl = new PrescriptionCancelPortImpl(prescriptionService);

        impl.cancel("R20260921000001", "医生改方");
        verify(prescriptionService).cancel("R20260921000001", "医生改方");

        // 已缴费拒 PH-1014 异常原样上抛：消费方（M03 引导链）按既有 PH 码契约处置
        BizException reject = new BizException(
                PharmacyErrorCode.RX_CANCEL_BLOCKED_AFTER_CHARGE, HttpStatus.CONFLICT, "处方已缴费不可作废：R20260921000001");
        doThrow(reject).when(prescriptionService).cancel("R20260921000001", "医生改方");

        assertThatThrownBy(() -> impl.cancel("R20260921000001", "医生改方")).isSameAs(reject);
    }
}
