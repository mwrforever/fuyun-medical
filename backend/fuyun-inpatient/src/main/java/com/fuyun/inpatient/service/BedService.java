package com.fuyun.inpatient.service;

import com.fuyun.inpatient.dto.BedAssignRequest;
import com.fuyun.inpatient.enums.TransferType;
import com.fuyun.inpatient.vo.BedMapVO;
import java.util.List;

/**
 * 床位管理域服务（FU-M04-02）：床位五态状态机（BedStatus）权威面——全部状态迁移一律 CAS
 * 条件更新 + 影响行数判定（防重复占床硬防线：仅 FREE/RESERVED 可占床，IP-1006；消毒/维修
 * 中分配拒绝 IP-1005），每次迁移事务内广播 inpatient.bed.changed（V800 id 52）。占用流水
 * （bed_assign 只增表）与床位图聚合同源本服务；入院登记域（AdmissionService）的床位联动
 * 面（reserveForAdmission/releaseForAdmission/occupyForAdmission）与转科/转床编排
 * （TransferService）的床位流转面（transferOut/occupyForTransfer）均由本服务承载。
 */
public interface BedService {

    /**
     * 病区床位图聚合（GET /beds/map?wardId=）：床位五态 + 包床标记 + 性别限制 + 占用 visit
     * 摘要（脱敏：仅定位键与入科时点）。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @return 床位图行清单（按床号升序；病区无床返回空清单），非空
     */
    List<BedMapVO> bedMap(String wardId);

    /**
     * 床位预占（FREE→RESERVED）：登记台签床/转科预占/全院一张床共用入口；预占不绑定就诊
     * 主体（visit_id 登记确认才签发）。成功广播 inpatient.bed.changed（patientId=null）。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     * @throws com.fuyun.common.exception.BizException IP-1004（404 床位不存在）/
     *                 IP-1005（409 消毒/维修中禁止预占）/ IP-1006（409 已预占/已占用——并发预占同判）
     */
    void reserve(Long bedId);

    /**
     * 床位占床（FREE/RESERVED→OCCUPIED，直接分配快速通道）：占用主体经就诊号解析（仅
     * REGISTERED/ADMITTED 态可分配），开 bed_assign 占用流水（assign_type=ADMISSION）并
     * 广播 inpatient.bed.changed（patientId=占用患者）。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     * @param req   占床入参（占用主体就诊号），非空；来源：护士站分配床位
     * @throws com.fuyun.common.exception.BizException IP-1004（404 床位不存在）/
     *                 IP-1005（409 消毒/维修中禁止分配）/ IP-1006（409 已占用——CAS 0 行并发占床同判）/
     *                 IP-1007（404 就诊不存在）/ IP-1008（409 就诊非待入科/在院态禁止分配床位）
     */
    void assign(Long bedId, BedAssignRequest req);

    /**
     * 释放预占（RESERVED→FREE）：住院证作废（SCHEDULED 态）联动入口与登记台手工释放共用。
     * 占用态（OCCUPIED）床位禁手工释放——须走转床/转科/出院编排流转。成功广播
     * inpatient.bed.changed（patientId=null）。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     * @throws com.fuyun.common.exception.BizException IP-1004（404 床位不存在）/
     *                 IP-1005（409 非预占态禁止释放——空床/占用/消毒/维修）
     */
    void release(Long bedId);

    /**
     * 消毒完成确认（DISINFECTING→FREE）：终末消毒完成，床位回可分配池。成功广播
     * inpatient.bed.changed（patientId=null）。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     * @throws com.fuyun.common.exception.BizException IP-1004（404 床位不存在）/
     *                 IP-1005（409 非消毒中态禁止完成确认）
     */
    void disinfectDone(Long bedId);

    /**
     * 床位转维修（FREE→MAINTENANCE）：维修停用（占用/预占/消毒中床位禁转维修）。成功广播
     * inpatient.bed.changed（patientId=null）。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     * @throws com.fuyun.common.exception.BizException IP-1004（404 床位不存在）/
     *                 IP-1005（409 非空床态禁止转维修）
     */
    void maintain(Long bedId);

    /**
     * 维修恢复（MAINTENANCE→FREE）：维修完成回可分配池。成功广播 inpatient.bed.changed
     * （patientId=null）。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     * @throws com.fuyun.common.exception.BizException IP-1004（404 床位不存在）/
     *                 IP-1005（409 非维修中态禁止恢复）
     */
    void maintainDone(Long bedId);

    /**
     * 预约入院床位预占联动（AdmissionService.schedule 面，Task 3 联动①）：住院证预约
     * （WAITING→SCHEDULED）同事务内将目标床位置 RESERVED；预占失败（被占/消毒/维修）则
     * 整体预约事务回滚——预约到不可用床必须整体失败。
     *
     * @param bedId 目标床位 id，非空；来源：预约入参 targetBedId
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005/IP-1006（同 reserve 全清单）
     */
    void reserveForAdmission(Long bedId);

    /**
     * 预约作废床位释放联动（AdmissionService.cancel 面，Task 3 联动②）：住院证作废
     * （SCHEDULED 态）同事务内释放预占床位（RESERVED→FREE）；WAITING 态作废无预占不触达。
     *
     * @param bedId 预占床位 id，非空；来源：住院证行 target_bed_id
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005（同 release 全清单）
     */
    void releaseForAdmission(Long bedId);

    /**
     * 入科确认占床联动（AdmissionService.admitWard 面，Task 3 联动③）：visit
     * REGISTERED→ADMITTED 同事务内目标床位 RESERVED→OCCUPIED（CAS 防重）并开 bed_assign
     * 占用流水（assign_type=ADMISSION），广播 inpatient.bed.changed；占床失败整体入科
     * 事务回滚。
     *
     * @param bedId     入科床位 id，非空；来源：入科入参 bedId
     * @param visitId   住院就诊号（I 型 14 位），非空；来源：路径参数
     * @param patientId 占用患者主索引，非空；来源：就诊行（admit-ward 已权威读取，免二次解析）
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005/IP-1006（同 assign 床位面全清单）
     */
    void occupyForAdmission(Long bedId, String visitId, long patientId);

    /**
     * 转出床流转（TransferService 编排面）：OCCUPIED→DISINFECTING 终末消毒流转（占用主体
     * 双条件校验防误流转他人床位），闭合 bed_assign 未继行（ended_at=转移时点），广播
     * inpatient.bed.changed（patientId=null）。
     *
     * @param bedId   转出床位 id，非空；来源：就诊行 current_bed_id
     * @param visitId 转出主体住院就诊号（I 型 14 位），非空；来源：编排起始就诊行
     * @throws com.fuyun.common.exception.BizException IP-1004（404 床位不存在）/
     *                 IP-1005（409 非占用态禁止流转）/ IP-1023（409 占用主体不符或并发流转、无未闭合流水）
     */
    void transferOut(Long bedId, String visitId);

    /**
     * 目标床占床（TransferService 编排面）：FREE/RESERVED→OCCUPIED（CAS 防重）并开
     * bed_assign 占用流水（assign_type 按 TransferType 取 BED_CHANGE/WARD_TRANSFER），
     * 广播 inpatient.bed.changed（patientId=转移患者）。
     *
     * @param bedId     目标床位 id，非空；来源：编排入参
     * @param visitId   住院就诊号（I 型 14 位），非空；来源：编排起始就诊行
     * @param patientId 转移患者主索引，非空；来源：就诊行
     * @param type      编排类型（BED_CHANGE 转床/WARD_TRANSFER 转科转入——决定流水 assign_type），非空
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005/IP-1006（同 assign 床位面全清单）
     */
    void occupyForTransfer(Long bedId, String visitId, long patientId, TransferType type);
}
