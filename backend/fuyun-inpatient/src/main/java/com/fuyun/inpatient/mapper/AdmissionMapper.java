package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.Admission;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 住院证 mapper：单表链式能力 + 状态条件更新注解 SQL 全集（GC26：条件更新一律 @Update +
 * 影响行数判定，显式补 deleted=0；状态字面量与 V902 列值域、AdmissionStatus code 逐字同源）。
 * 目标床位预占/释放联动（BedService.reserveForAdmission/releaseForAdmission）已随 V903 bed
 * 落地（Task 4 服务层同事务联动）；本层承载 admission 自身状态面。
 */
@Mapper
public interface AdmissionMapper extends BaseMapper<Admission> {

    /**
     * 预约入院 CAS（WAITING→SCHEDULED）：目标病区/床位与预约日期同语句落值。
     *
     * @param admissionNo  住院证号，非空
     * @param targetWardId 目标病区编码，可空（全院一张床跨病区签床场景允许病区缺席）
     * @param targetBedId  目标床位 id，可空（预住院模式允许无床虚拟登记）
     * @param expectDate   预约入院日期，非空
     * @param updatedBy    操作者（审计留痕），非空
     * @return 影响行数（0=非 WAITING 态或证不存在，调用方定性 IP-1001/IP-1002）
     */
    @Update("UPDATE inpatient.admission SET status = 'SCHEDULED', target_ward_id = #{targetWardId}, "
            + "target_bed_id = #{targetBedId}, expect_date = #{expectDate}, updated_by = #{updatedBy} "
            + "WHERE admission_no = #{admissionNo} AND status = 'WAITING' AND deleted = 0")
    int casSchedule(
            @Param("admissionNo") String admissionNo,
            @Param("targetWardId") String targetWardId,
            @Param("targetBedId") Long targetBedId,
            @Param("expectDate") LocalDate expectDate,
            @Param("updatedBy") String updatedBy);

    /**
     * 住院证作废 CAS（WAITING/SCHEDULED→CANCELLED，终态）。目标床位预占释放由服务层同事务
     * 宽容联动（回读权威 target_bed_id 与床行实态，仅 RESERVED 才 BedService.releaseForAdmission）。
     *
     * @param admissionNo 住院证号，非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=终态（COMPLETED/CANCELLED）不可作废，调用方定性 IP-1002）
     */
    @Update("UPDATE inpatient.admission SET status = 'CANCELLED', updated_by = #{updatedBy} "
            + "WHERE admission_no = #{admissionNo} AND status IN ('WAITING', 'SCHEDULED') AND deleted = 0")
    int casCancel(@Param("admissionNo") String admissionNo, @Param("updatedBy") String updatedBy);

    /**
     * 登记确认 CAS（WAITING/SCHEDULED→COMPLETED，终态）：与 visit_id 签发同事务（红线——
     * 证状态迁移与就诊落库同事务成败与共）。
     *
     * @param admissionNo 住院证号，非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=终态证禁止登记确认，调用方定性 IP-1002）
     */
    @Update("UPDATE inpatient.admission SET status = 'COMPLETED', updated_by = #{updatedBy} "
            + "WHERE admission_no = #{admissionNo} AND status IN ('WAITING', 'SCHEDULED') AND deleted = 0")
    int casComplete(@Param("admissionNo") String admissionNo, @Param("updatedBy") String updatedBy);
}
