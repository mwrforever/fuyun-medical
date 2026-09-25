package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.InpatientVisit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 住院就诊 mapper：单表链式能力 + 入科确认状态条件更新注解 SQL（GC26：条件更新一律 @Update +
 * 影响行数判定，显式补 deleted=0；状态字面量与 V902 列值域、VisitStatus code 逐字同源）。
 * 床位 RESERVED→OCCUPIED 流转与 bed_assign 占用流水开账归 Task 4（BedService）随 V903 落地
 * 后补齐——本层先承载 visit 自身状态面。
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
}
