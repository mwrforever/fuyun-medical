package com.fuyun.pharmacy.api;

/**
 * 处方作废对接端口（M03 处方引用作废引导链的进程内入口，api 包契约；PrescriptionOpenPort 先例
 * 同型——禁 HTTP 自调用）。语义与 REST {@code POST /api/v1/pharmacy/prescriptions/{no}/cancel}
 * 逐字同源：仅未缴费（APPROVED/PENDING_FEE）可作废并同事务联动 billing PENDING 费用行作废；
 * 已缴费拒 PH-1014 引导退费链，口径不变。
 */
public interface PrescriptionCancelPort {

    /**
     * 作废转调（既有 cancel 主链零旁路：状态守卫/费用联动/事件发布全复用；REQUIRED 传播加入调用方
     * 事务，异常原样上抛不吞）。
     *
     * @param rxNo   处方号，非空；来源：调用方业务号引用
     * @param reason 作废原因，非空白（审计留痕锚点）
     * @throws com.fuyun.common.exception.BizException PH-1004（处方不存在 404）/PH-1005（状态机
     *                                                  违例 409）/PH-1014（已缴费拒作废 409——
     *                                                  引导收费窗口退费或药房退药受理）时触发
     */
    void cancel(String rxNo, String reason);
}
