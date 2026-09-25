package com.fuyun.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 住院计费联动服务（M13 住院域事件消费业务体唯一承载，P2 PR-1 Task 13）：六事件消费的
 * 起费/计价/确认/截断/切分/停费编排与床位费日切。事务边界归本服务（监听器仅解析与派发），
 * 计价一律经 {@link IPricingEngineService#generateFromSource} 既有通道（计费唯一键防重、
 * 价格快照冻结复用，禁第二套计价路径）；金额零取自事件载荷（inpatient 载荷本无金额，
 * 计价权威在 billing 计价引擎）。
 */
public interface IInpatientChargeService {

    /**
     * 患者入科消费（inpatient.visit.admitted，V800 id 48）：落住院费用归属起费锚点
     * （fee_ownership_split ADMIT_START 行，重复投递幂等跳过）+ 当日床位费 PENDING 行
     * （DAY_CUTOVER/DURATION 通道，床位费种子价×1，计费唯一键 visit×日幂等）。
     *
     * @param visitId    住院就诊号，非空；来源：事件载荷
     * @param patientId  患者主索引，非空；来源：事件载荷
     * @param wardId     入科病区编码，非空；来源：事件载荷（锚点 to_ward_id 承载）
     * @param admittedAt 入科时点，非空；来源：事件载荷（锚点 split_at 透传）
     */
    void onVisitAdmitted(String visitId, long patientId, String wardId, Instant admittedAt);

    /**
     * 医嘱开立消费（inpatient.order.created，V901 id 66；通配绑定到达含类型子键帧）：
     * 住院离散计价——按医嘱明细逐项生成 PENDING 费用行（ORDER_LINKED/ORDER_CONFIRMED 通道，
     * sourceRef=医嘱号，计费唯一键同单同项目同日防重）。created 为离散计价唯一数据面：
     * audited 冻结载荷（V800 id 41 六字段）不携 items，适配留痕见监听器。
     *
     * @param m04OrderNo 医嘱号，非空；来源：事件载荷
     * @param visitId    住院就诊号，非空；来源：事件载荷
     * @param patientId  患者主索引，非空；来源：事件载荷
     * @param items      医嘱明细行数组（itemCode/quantity 逐行计价），非空数组；来源：事件载荷
     * @throws IllegalStateException 明细行缺 itemCode/quantity（不合规帧，死信留痕）时触发
     */
    void onOrderCreated(String m04OrderNo, String visitId, long patientId, JsonNode items);

    /**
     * 医嘱执行回签消费（inpatient.order.executed，V800 id 47）：该医嘱全部 PENDING 费用行
     * PENDING→CONFIRMED（执行回签=住院费用入账权威时点）；消费侧直改状态不发事件
     * （billing.fee.confirmed 常量无调用点保持，brief 冻结语义）。重复投递零行幂等达成。
     *
     * @param m04OrderNo 医嘱号，非空；来源：事件载荷
     * @param visitId    住院就诊号，非空；来源：事件载荷
     */
    void onOrderExecuted(String m04OrderNo, String visitId);

    /**
     * 医嘱终态费用截断（stopped/cancelled/revoked/audit-rejected 四事件共用入口，V800 id 44/45/46、
     * V901 id 67）：四者同属「医嘱不可能再执行」终态——该医嘱未确认 PENDING 费用行截断作废
     * （PENDING→CANCELLED；已确认/已结算行不回冲），防未结清合计虚增误导出院费用预审。
     * 重复投递零行幂等达成。
     *
     * @param m04OrderNo 医嘱号，非空；来源：事件载荷
     * @param visitId    住院就诊号，非空；来源：事件载荷
     */
    void onOrderStopped(String m04OrderNo, String visitId);

    /**
     * 转科/转床消费（inpatient.visit.transferred，V800 id 49）：费用归属切分落行
     * （fee_ownership_split TRANSFER 行，visit/from_ward/to_ward/split_at——床日费按时间线
     * 切分归 P3，本 PR 落切分点记录）。
     *
     * @param visitId       住院就诊号，非空；来源：事件载荷
     * @param patientId     患者主索引，非空；来源：事件载荷
     * @param fromWardId    转出病区编码，非空；来源：事件载荷
     * @param toWardId      转入病区编码，非空；来源：事件载荷
     * @param transferredAt 转移时点，非空；来源：事件载荷
     */
    void onVisitTransferred(String visitId, long patientId, String fromWardId, String toWardId, Instant transferredAt);

    /**
     * 出院申请消费（inpatient.visit.discharge-requested，V800 id 50）：停止持续性计费标记
     * （fee_ownership_split DISCHARGE_STOP 行，重复投递幂等跳过）——日切任务据此跳过出院就诊，
     * 出院后不再生成床位费。
     *
     * @param visitId     住院就诊号，非空；来源：事件载荷
     * @param patientId   患者主索引，非空；来源：事件载荷
     * @param requestedAt 申请时点，非空；来源：事件载荷
     */
    void onDischargeRequested(String visitId, long patientId, Instant requestedAt);

    /**
     * 床位费日切（02:30 全院在院就诊，InpatientDailyChargeJob 调度）：对每一名有起费锚点且
     * 无停费标记的在院就诊生成当日床位费 PENDING 行（计费唯一键 visit×日幂等，重复执行零新增）。
     * 单就诊失败不阻断全院批次（重复计费幂等跳过，其余错误 error 留痕续行——夜间批次可用性优先，
     * 漏费经日志对账补偿）。
     *
     * @return 本轮新生成床位费行数（幂等跳过与失败不计入）
     */
    int dailyBedCharge();
}
