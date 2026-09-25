package com.fuyun.inpatient.service;

import com.fuyun.inpatient.dto.ChangeBedRequest;
import com.fuyun.inpatient.dto.TransferRequest;
import com.fuyun.inpatient.vo.TransferResultVO;

/**
 * 护理单元变更编排服务（FU-M04-02，04-inpatient Spec §3.5）：转科四阶段编排与同病区转床
 * 轻量路径。转科/转床均非 visit 状态变更（ADMITTED 内属性变更，独立于就诊状态机），单
 * @Transactional 编排事务内完成并发布 inpatient.visit.transferred（V800 id 49）。
 */
public interface TransferService {

    /**
     * 转科四阶段编排（单事务，时序冻结）：①转出病区全部长期医嘱自动停嘱
     * （MedicalOrderService.stopAllForTransfer，Task 5 impl）②在途三分（医嘱停嘱由①承载；
     * 计划三分数据面操作归 Task 7/8 计划服务补挂转科钩子——本编排只留钩子面；费用不改写归
     * M13）③床位流转（转出床→DISINFECTING 闭合流水、目标床 CAS 占床开新流水、visit
     * current_ward/current_bed/current_dept 原子更新）④发布 inpatient.visit.transferred
     * （六字段载荷）。任一阶段失败整体回滚（转出床状态自动复原）。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：路径参数
     * @param req     转科入参（目标科室/病区/床位），非空；来源：医生站转科单
     * @return 编排出参（前后定位面与完成时点），非空
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）/
     *                 IP-1008（409 非在院态禁止转科）/ IP-1004（404 目标床位不存在）/
     *                 IP-1022（400 目标病区与当前病区相同或目标床位与当前床位相同——同病区走转床路径）/
     *                 IP-1023（409 目标床位与目标病区归属不符/在院无床位/占用主体不符/并发出院）
     */
    TransferResultVO transfer(String visitId, TransferRequest req);

    /**
     * 同病区转床轻量路径（单事务，无医嘱停嘱步骤）：转出床→DISINFECTING 闭合流水 → 目标床
     * CAS 占床开流水（assign_type=BED_CHANGE）→ visit current_bed 原子更新 → 发布
     * inpatient.visit.transferred（前后病区相同）。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：路径参数
     * @param req     转床入参（目标床位），非空；来源：护士站床位调整
     * @return 编排出参（前后定位面与完成时点），非空
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）/
     *                 IP-1008（409 非在院态禁止转床）/ IP-1004（404 目标床位不存在）/
     *                 IP-1022（400 目标床位与当前床位相同）/ IP-1023（409 目标床位跨病区/在院无床位/
     *                 占用主体不符/并发出院）
     */
    TransferResultVO changeBed(String visitId, ChangeBedRequest req);
}
