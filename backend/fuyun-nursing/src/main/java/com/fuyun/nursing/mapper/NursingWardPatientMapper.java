package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.NursingWardPatient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 病区患者本地视图 mapper：单表链式能力 + 条件更新注解 SQL 全集（GC26：条件更新一律 @Update +
 * 影响行数判定，显式补 deleted=0；状态/患者收敛字面量与 V801 列值域、WardPatientStatus code 逐字同源）。
 * 全部 @Update 均单表单语句、零级联——过渡通道退役前禁任何跨表写（GC38 触达最小护栏的可执行锚）。
 */
@Mapper
public interface NursingWardPatientMapper extends BaseMapper<NursingWardPatient> {

    /**
     * 移出病区一览 CAS（IN_WARD→REMOVED）：仅置视图状态 + 操作者审计留痕（GC38 四护栏——零外发、
     * 单表单语句、reason 不落库、无住院业务状态变更）。床位移出后占用谓词（status='IN_WARD'）自然解除。
     *
     * @param visitId   住院就诊号，非空
     * @param updatedBy 移出操作者（审计留痕），非空
     * @return 影响行数（0=在区行不存在，调用方定性 NS-1001）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET status = 'REMOVED', updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND status = 'IN_WARD' AND deleted = 0")
    int casRemove(@Param("visitId") String visitId, @Param("updatedBy") String updatedBy);

    /**
     * 过敏标识订阅刷新（patient.health-summary.updated 消费体）：按患者归一 IN_WARD 行批量置位。
     *
     * @param patientId  患者主索引（载荷原文，不再二次归一），非空
     * @param hasAllergy 载荷当前过敏态，非空
     * @return 影响行数（0=该患者无在区行，幂等容忍）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET allergy_flag = #{hasAllergy} "
            + "WHERE patient_id = #{patientId} AND status = 'IN_WARD' AND deleted = 0")
    int updateAllergyFlag(@Param("patientId") long patientId, @Param("hasAllergy") boolean hasAllergy);

    /**
     * 患者合并收敛（patient.patient.merged 消费体）：被合并从档的在区行收敛到存活主档（CF-3 归一语义）。
     *
     * @param mergedPatientId    被合并从档 id（载荷），非空
     * @param survivorPatientId  存活主档 id（载荷），非空
     * @return 影响行数（0=无从档在区行，幂等容忍）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET patient_id = #{survivorPatientId} "
            + "WHERE patient_id = #{mergedPatientId} AND status = 'IN_WARD' AND deleted = 0")
    int casMergePatient(
            @Param("mergedPatientId") long mergedPatientId, @Param("survivorPatientId") long survivorPatientId);

    /**
     * 患者拆分还原（patient.patient.split 消费体，merged 的成对逆映射 M-25）：原从档恢复 NORMAL 时
     * 把收敛到主档的在区行按 restoredPatientId 还原。P1 残余限制：无法区分主档自有行与合并迁入行，
     * 主档名下在区行整体还原——过渡视图 P2 随事件链退役，残余随退役消失（2026-09-22 用户明示接受）。
     *
     * @param survivorPatientId 原主档 id（载荷），非空
     * @param restoredPatientId 恢复 NORMAL 的原从档 id（载荷），非空
     * @return 影响行数（0=无在区行，幂等容忍）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET patient_id = #{restoredPatientId} "
            + "WHERE patient_id = #{survivorPatientId} AND status = 'IN_WARD' AND deleted = 0")
    int casSplitPatient(
            @Param("survivorPatientId") long survivorPatientId, @Param("restoredPatientId") long restoredPatientId);

    /**
     * 风险标识回写（appendRiskFlag 消费体，Task 8 评估高危回写）：整体置合并后的逗号分隔值
     * （合并/去重逻辑归服务层，本语句仅承载最终值）。
     *
     * @param visitId   住院就诊号，非空
     * @param riskFlags 合并后的风险标识串（逗号分隔），非空
     * @param updatedBy 回写操作者（评估流程操作者，审计留痕），非空
     * @return 影响行数（0=在区行不存在，调用方定性 NS-1001）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET risk_flags = #{riskFlags}, updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND status = 'IN_WARD' AND deleted = 0")
    int updateRiskFlags(
            @Param("visitId") String visitId,
            @Param("riskFlags") String riskFlags,
            @Param("updatedBy") String updatedBy);
}
