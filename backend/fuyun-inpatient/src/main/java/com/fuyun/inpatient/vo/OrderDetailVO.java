package com.fuyun.inpatient.vo;

import java.util.List;

/**
 * 住院医嘱详情出参（GET /api/v1/inpatient/orders/{no}）：医嘱头字段与明细行全集
 * （MedicalOrderVO 头面 + items 明细面），闭环追溯取数入口。
 *
 * @param order 医嘱头出参，非空
 * @param items 医嘱明细行出参（item_seq 升序），非空
 */
public record OrderDetailVO(MedicalOrderVO order, List<OrderItemVO> items) {}
