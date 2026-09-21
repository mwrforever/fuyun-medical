package com.fuyun.billing.api;

import java.util.List;

/**
 * 门诊域退费申请命令（OutpatientBillingPort.applyRefund 入参，M03/M13 进程内对接面）：
 * 退号退费统一免审档通道的命令载体（裁决 7）——金额不由命令携带，M13 服务端按明细单价快照×
 * 数量算得（红线 1 退侧同源）。quantity 为 DECIMAL string 承载（D-18 同源），转调侧转 BigDecimal。
 *
 * @param settlementId 原结算单 id（退费资金原路退回与分级判定锚点；M03 侧来源=appointment.fee_settlement_id）
 * @param lines        退费明细行清单，非空；每行对应一条 refund_fee_link 负向台账
 * @param reason       退费理由，非空白（审计留痕要素；M03 侧来源=退号操作者录入原因）
 */
public record VisitRefundCommand(long settlementId, List<Line> lines, String reason) {

    /**
     * 退费明细行（RefundLine 的进程内镜像：M03 侧不感知 billing dto，api 包为唯一出口）。
     *
     * @param feeId    原费用明细 id（billing.fee_record 行），非空
     * @param quantity 本次退费数量（DECIMAL string，须为数字串且 &gt;0——转调侧解析守卫由端口 javadoc
     *                 契约承载；挂号费整号退=单行 "1" 口径）
     */
    public record Line(long feeId, String quantity) {}
}
