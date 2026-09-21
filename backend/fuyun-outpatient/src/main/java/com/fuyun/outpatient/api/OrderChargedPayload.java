package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 门诊缴费放行扇出事件载荷（outpatient.order.charged，V204 id 25 冻结契约——V702 占位经 UPDATE 升级正式载荷）：
 * 结算完成同事务发布，M06（本仓 pharmacy）按 rxNos 单据精确放行（裁决 4）、M07/M08/M05 执行放行随 P3。
 *
 * @param settlementId     结算单 id（雪花，M13 结算聚合根标识）
 * @param settleNo         结算单业务号（收据/对账锚点）
 * @param patientId        患者主索引
 * @param visitId          CF-3 门诊就诊号
 * @param orderNos         本次结算覆盖的申请单号精确清单（与结算明细一一对应，消费方按单放行禁全量扫）
 * @param rxNos            本次结算覆盖的处方号精确清单（M06 单据精确放行清单，裁决 4）
 * @param greenChannelFlag 绿通标识（true=绿通位结算，放行链路不因欠费拦截，执行侧照常）
 */
public record OrderChargedPayload(
        Long settlementId,
        String settleNo,
        Long patientId,
        String visitId,
        List<String> orderNos,
        List<String> rxNos,
        boolean greenChannelFlag) {

    /** 顶层组件名清单（契约测试与 V204 id 25 UPDATE 后 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES =
            List.of("settlementId", "settleNo", "patientId", "visitId", "orderNos", "rxNos", "greenChannelFlag");
}
