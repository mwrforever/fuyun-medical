package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.InpatientVisit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 住院就诊 mapper：单表链式能力 + 状态条件更新注解 SQL 全集（GC26：条件更新一律 @Update +
 * 影响行数判定，显式补 deleted=0；状态字面量与 V902 列值域、VisitStatus code 逐字同源）。
 * 床位 RESERVED→OCCUPIED 流转与 bed_assign 占用流水开账的权威归 BedService（V903，Task 4
 * 已补齐联动）；本层承载 visit 自身状态面与转科/转床的 current_* 原子更新；欠费标识刷新
 * （Task 10 billing.deposit.changed 消费面）为就诊行本地属性 CAS，与五态状态机无涉。
 */
@Mapper
public interface InpatientVisitMapper extends BaseMapper<InpatientVisit> {

    /**
     * 入科确认 CAS（REGISTERED→ADMITTED）：当前科室/病区/床位、主治医生与护理级别同语句落值，
     * 入科时点取库端 now()（禁应用时钟）。
     *
     * @param visitId            住院就诊号（I 型 14 位），非空
     * @param deptId             入科科室编码，可空（病区归属科室缺席时容许）
     * @param wardId             入科病区编码，非空
     * @param bedId              入科床位 id，非空
     * @param attendingDoctorId  主治医生，可空（医生站后补维护）
     * @param nursingLevel       护理级别（SPECIAL/CRITICAL/NORMAL），非空
     * @param updatedBy          操作者（审计留痕），非空
     * @return 影响行数（0=非 REGISTERED 态（已入科/已出院/已作废），调用方定性 IP-1008）
     */
    @Update("UPDATE inpatient.inpatient_visit SET status = 'ADMITTED', current_dept_id = #{deptId}, "
            + "current_ward_id = #{wardId}, current_bed_id = #{bedId}, "
            + "attending_doctor_id = #{attendingDoctorId}, nursing_level = #{nursingLevel}, "
            + "admitted_at = now(), updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND status = 'REGISTERED' AND deleted = 0")
    int casAdmitWard(
            @Param("visitId") String visitId,
            @Param("deptId") String deptId,
            @Param("wardId") String wardId,
            @Param("bedId") Long bedId,
            @Param("attendingDoctorId") String attendingDoctorId,
            @Param("nursingLevel") String nursingLevel,
            @Param("updatedBy") String updatedBy);

    /**
     * 转科/转床定位 CAS（ADMITTED 内属性变更，不改状态）：当前病区/床位原子更新，转科时
     * 目标科室随语句落值（缺席时保留原值——&lt;if&gt; 动态拼装）。限定在院态（转科/转床为
     * ADMITTED 内属性变更，独立于状态机——已出院/已作废一律 0 行）。
     *
     * @param visitId   住院就诊号（I 型 14 位），非空
     * @param toDeptId  目标科室编码，可空（缺席保留原值；转床轻量路径不传）
     * @param toWardId  目标病区编码，非空
     * @param toBedId   目标床位 id，非空
     * @param updatedBy 操作者（审计留痕），非空
     * @return 影响行数（0=非 ADMITTED 态（并发出院/作废），调用方定性 IP-1023）
     */
    @Update("<script>UPDATE inpatient.inpatient_visit SET current_ward_id = #{toWardId}, "
            + "current_bed_id = #{toBedId}"
            + "<if test='toDeptId != null'>, current_dept_id = #{toDeptId}</if>"
            + ", updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND status = 'ADMITTED' AND deleted = 0</script>")
    int casTransferLocation(
            @Param("visitId") String visitId,
            @Param("toDeptId") String toDeptId,
            @Param("toWardId") String toWardId,
            @Param("toBedId") Long toBedId,
            @Param("updatedBy") String updatedBy);

    /**
     * 出院申请 CAS（ADMITTED→DISCHARGE_REQUESTED，DischargeService.createRequest 面）：
     * 申请时点取库端 now()（禁应用时钟）；限定在院态（并发重复申请/已出院/已作废一律 0 行，
     * 与 discharge_request 的 uk_visit_active 双防线）。
     *
     * @param visitId   住院就诊号（I 型 14 位），非空
     * @param updatedBy 操作者（审计留痕），非空
     * @return 影响行数（0=非 ADMITTED 态，调用方定性 IP-1008/IP-1023）
     */
    @Update("UPDATE inpatient.inpatient_visit SET status = 'DISCHARGE_REQUESTED', "
            + "discharge_requested_at = now(), updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND status = 'ADMITTED' AND deleted = 0")
    int casRequestDischarge(@Param("visitId") String visitId, @Param("updatedBy") String updatedBy);

    /**
     * 取消出院 CAS（DISCHARGE_REQUESTED→ADMITTED，DischargeService.cancel 面）：取消申请回
     * 在院（Spec §5 状态机冻结边）；申请时点保留（历史留痕不清抹）。
     *
     * @param visitId   住院就诊号（I 型 14 位），非空
     * @param updatedBy 操作者（审计留痕），非空
     * @return 影响行数（0=非 DISCHARGE_REQUESTED 态——并发离院确认/作废，调用方定性 IP-1023）
     */
    @Update("UPDATE inpatient.inpatient_visit SET status = 'ADMITTED', updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND status = 'DISCHARGE_REQUESTED' AND deleted = 0")
    int casCancelDischarge(@Param("visitId") String visitId, @Param("updatedBy") String updatedBy);

    /**
     * 离院确认 CAS（DISCHARGE_REQUESTED→DISCHARGED 终态，DischargeService.confirm 面）：
     * 出院完成时点取库端 now()（禁应用时钟）并落离院方式（病案首页代码誊写面）；离院确认
     * 双条件（申请 READY+结算标记）已由服务层 GC19 校验裁决，本 CAS 兜底并发窗口。
     *
     * @param visitId     住院就诊号（I 型 14 位），非空
     * @param dischargeWay 离院方式（DischargeWay 病案首页代码），非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=非 DISCHARGE_REQUESTED 态——调用方定性 IP-1023）
     */
    @Update("UPDATE inpatient.inpatient_visit SET status = 'DISCHARGED', discharged_at = now(), "
            + "discharge_way = #{dischargeWay}, updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND status = 'DISCHARGE_REQUESTED' AND deleted = 0")
    int casDischarge(
            @Param("visitId") String visitId,
            @Param("dischargeWay") String dischargeWay,
            @Param("updatedBy") String updatedBy);

    /**
     * 欠费标识 CAS（billing.deposit.changed 消费面，AdmissionService.onDepositChanged）：
     * 目标值异于现值才更新（arrears_flag &lt;&gt; 目标值限定），updated_at 由 V902 触发器刷新
     * （近似承载标识时点——无专用置位列，零新迁移红线）。不限定状态面：欠费标识为就诊行本地
     * 属性，与五态状态机无涉（护士站清单聚合侧再限在院态）。
     *
     * @param visitId   住院就诊号（I 型 14 位），非空
     * @param flag      目标欠费标识（true=余额跌破押金下限置位 / false=回升复位）
     * @param updatedBy 操作者（消费线程回退 system，审计留痕），非空
     * @return 影响行数（0=标识已处目标态（重复投递幂等）或就诊行不存在（无住院就诊的押金
     *         账户）——调用方 info 留痕直返）
     */
    @Update("UPDATE inpatient.inpatient_visit SET arrears_flag = #{flag}, updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND arrears_flag <> #{flag} AND deleted = 0")
    int casUpdateArrearsFlag(
            @Param("visitId") String visitId, @Param("flag") boolean flag, @Param("updatedBy") String updatedBy);
}
