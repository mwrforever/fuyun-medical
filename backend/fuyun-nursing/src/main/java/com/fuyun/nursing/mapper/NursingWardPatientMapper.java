package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.NursingWardPatient;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 病区患者投影 mapper（W-34 退役后纯事件投影写面）：单表链式能力 + 条件更新注解 SQL 全集
 * （GC26：条件更新一律 @Update + 影响行数判定，显式补 deleted=0）。V1108 已 DROP status/
 * source 两列——在册语义由 deleted=0 单独承载（uk_ward_patient_visit/uk_ward_patient_bed
 * 均带 deleted=0 谓词，discharged 逻辑删后同 visitId 可重新 upsert）。投影写入单一入口=
 * InpatientVisitEventListener 四路（admitted upsert / transferred 归属 / discharged 逻辑删 /
 * bed.changed 补床号），全部 CAS/幂等（事件重放零副作用）。订阅面（合并/拆分/过敏刷新）与
 * 评估域风险标识回写仍为既有条件更新（谓词随列退役同步收敛 deleted=0 单承载）。
 * 全部 @Update 均单表单语句、零级联。
 */
@Mapper
public interface NursingWardPatientMapper extends BaseMapper<NursingWardPatient> {

    /**
     * 入科属性刷新 CAS（visit.admitted 消费体——upsert 既有行分支与 uk 冲突回查合并分支共用）：
     * 刷新归属病区/护理级别/入科时点（事件重放同值覆盖，零语义副作用）。
     * 禁触 bed_no（床号唯一写入面=bed.changed，admitted 载荷仅携床位 id 无床号）与展示名
     * （详情卡经 patient api 嵌查承载，事件载荷脱敏红线不携姓名）。
     *
     * @param visitId      住院就诊号（投影行定位键），非空；来源：事件载荷
     * @param wardId       入科病区编码，非空；来源：事件载荷
     * @param nursingLevel 护理级别 code（NursingLevel 词表），非空；来源：事件载荷（缺省 NORMAL）
     * @param admittedAt   入科确认时点，非空；来源：事件载荷
     * @param updatedBy    消费链路操作者（审计留痕），非空
     * @return 影响行数（0=在册投影行不存在，调用方按幂等容忍定性）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET ward_id = #{wardId}, nursing_level = #{nursingLevel}, "
            + "admitted_at = #{admittedAt}, updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND deleted = 0")
    int casAdmitRefresh(
            @Param("visitId") String visitId,
            @Param("wardId") String wardId,
            @Param("nursingLevel") String nursingLevel,
            @Param("admittedAt") OffsetDateTime admittedAt,
            @Param("updatedBy") String updatedBy);

    /**
     * 投影归属更新 CAS（visit.transferred 消费体）：ward 切换到转入病区。
     * <b>bed_no 留旧值</b>（W-34 裁决：V800 id 49 载荷仅携 toBedId 床位 id 无床号，禁落 id 文本
     * 进床号语义列——待 bed.changed 事件补齐床号文本）。fromWardId 谓词承载幂等：重复投递时行
     * 已在 toWardId，0 行自然达成；跨病区并发转科由行锁串行化，后到事件 fromWard 不匹配即不误伤。
     *
     * @param visitId    住院就诊号（投影行定位键），非空；来源：事件载荷
     * @param fromWardId 转出病区编码（幂等谓词：仅迁出仍属原病区的行），非空；来源：事件载荷
     * @param toWardId   转入病区编码，非空；来源：事件载荷
     * @param updatedBy  消费链路操作者（审计留痕），非空
     * @return 影响行数（0=行不在册或已不属转出病区，幂等容忍）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET ward_id = #{toWardId}, updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND ward_id = #{fromWardId} AND deleted = 0")
    int casTransferWard(
            @Param("visitId") String visitId,
            @Param("fromWardId") String fromWardId,
            @Param("toWardId") String toWardId,
            @Param("updatedBy") String updatedBy);

    /**
     * 投影逻辑删 CAS（visit.discharged 消费体——W-34 口径禁物理删）：置 deleted=1 出在册面。
     * uk_ward_patient_visit 带 deleted=0 谓词，逻辑删后同 visitId 再入院经 admitted upsert 重新落行。
     *
     * @param visitId   住院就诊号（投影行定位键），非空；来源：事件载荷
     * @param updatedBy 消费链路操作者（审计留痕），非空
     * @return 影响行数（0=行已逻辑删或从未在册，幂等容忍）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET deleted = 1, updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND deleted = 0")
    int casDischarge(@Param("visitId") String visitId, @Param("updatedBy") String updatedBy);

    /**
     * 投影床号补齐 CAS（bed.changed 消费体——床号文本唯一写入面）：按占用患者主索引定位在册
     * 投影行更新床号。<b>不按 ward 过滤</b>：bed.changed 先于 transferred 到达时旧病区行先补
     * 新床号、transferred 后到切病区即收敛（双向乱序终态一致）。IS DISTINCT FROM 谓词承载重放
     * 幂等（床号已一致 0 行零副作用，PG NULL 安全等价）。
     *
     * @param patientId 占用患者主索引，非空；来源：事件载荷（仅占床/转入帧携带）
     * @param bedNo     床号文本，非空；来源：事件载荷
     * @param updatedBy 消费链路操作者（审计留痕），非空
     * @return 影响行数（0=该患者无在册投影行或床号已一致）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET bed_no = #{bedNo}, updated_by = #{updatedBy} "
            + "WHERE patient_id = #{patientId} AND deleted = 0 AND bed_no IS DISTINCT FROM #{bedNo}")
    int casUpdateBedNoByPatient(
            @Param("patientId") long patientId, @Param("bedNo") String bedNo, @Param("updatedBy") String updatedBy);

    /**
     * 过敏标识订阅刷新（patient.health-summary.updated 消费体）：按患者归一在册行批量置位。
     *
     * @param patientId 患者主索引（载荷原文，不再二次归一），非空
     * @param hasAllergy 载荷当前过敏态，非空
     * @return 影响行数（0=该患者无在册投影行，幂等容忍）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET allergy_flag = #{hasAllergy} "
            + "WHERE patient_id = #{patientId} AND deleted = 0")
    int updateAllergyFlag(@Param("patientId") long patientId, @Param("hasAllergy") boolean hasAllergy);

    /**
     * 患者合并收敛（patient.patient.merged 消费体）：被合并从档的在册行收敛到存活主档（CF-3 归一语义）。
     *
     * @param mergedPatientId    被合并从档 id（载荷），非空
     * @param survivorPatientId  存活主档 id（载荷），非空
     * @return 影响行数（0=无从档在册行，幂等容忍）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET patient_id = #{survivorPatientId} "
            + "WHERE patient_id = #{mergedPatientId} AND deleted = 0")
    int casMergePatient(
            @Param("mergedPatientId") long mergedPatientId, @Param("survivorPatientId") long survivorPatientId);

    /**
     * 患者拆分还原（patient.patient.split 消费体，merged 的成对逆映射 M-25）：原从档恢复 NORMAL 时
     * 把收敛到主档的在册行按 restoredPatientId 还原。残余限制：无法区分主档自有行与合并迁入行，
     * 主档名下在册行整体还原——过渡视图残余随 P2 事件链退役消失（2026-09-22 用户明示接受）。
     *
     * @param survivorPatientId 原主档 id（载荷），非空
     * @param restoredPatientId 恢复 NORMAL 的原从档 id（载荷），非空
     * @return 影响行数（0=无在册行，幂等容忍）
     */
    @Update("UPDATE nursing.nursing_ward_patient SET patient_id = #{restoredPatientId} "
            + "WHERE patient_id = #{survivorPatientId} AND deleted = 0")
    int casSplitPatient(
            @Param("survivorPatientId") long survivorPatientId, @Param("restoredPatientId") long restoredPatientId);

    /**
     * 风险标识原子追加（appendRiskFlag 消费体，Task 8 评估高危回写，EX-26）：单语句 DB 侧拼接——
     * 行级锁串行化并发追加互不覆盖（杜绝服务层读-改-写整串回写丢标记）；空串/NULL 首追加经 CASE
     * 直落标识（无前置逗号）；WHERE 侧「首尾补逗 position 定位」谓词与 Java 侧 tokens.contains
     * 逐字等价，拦截并发重复追加同标识（0 行幂等，不产生重复标记）。
     *
     * @param visitId   住院就诊号，非空
     * @param flag      风险标识 code（如 FALL/PRESSURE），非空
     * @param updatedBy 回写操作者（评估流程操作者，审计留痕），非空
     * @return 影响行数（0=在册行不存在或已含该标识，调用方按幂等容忍）
     */
    @Update("UPDATE nursing.nursing_ward_patient "
            + "SET risk_flags = CASE WHEN COALESCE(risk_flags, '') = '' THEN #{flag} "
            + "ELSE risk_flags || ',' || #{flag} END, updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND deleted = 0 "
            + "AND position(',' || #{flag} || ',' in ',' || COALESCE(risk_flags, '') || ',') = 0")
    int casAppendRiskFlag(
            @Param("visitId") String visitId, @Param("flag") String flag, @Param("updatedBy") String updatedBy);

    /**
     * 风险标识原子移除（removeRiskFlag 消费体，评估复评降级回写，EX-26 N7 收口与追加侧对称化）：
     * 单语句 DB 侧摘除——string_to_array→array_remove→array_to_string 原生数组三连仅摘目标标识
     * （剩余标识保持既有顺序，末位摘除落空串，契合 DDL NOT NULL 默认空串口径）；WHERE 侧
     * 「首尾补逗 position 定位」谓词与追加侧同形态取反向（当前值含该标识才施写），与追加侧
     * 共用行级锁串行化——并发追加/移除交错双向不互吞（杜绝服务层读快照拼剩余串整串置值
     * 抹掉并发追加标记的安全信号丢失窗口）。0 行 = 在册行不存在或当前值已不含该标识，
     * 调用方重读定性。
     *
     * @param visitId   住院就诊号，非空
     * @param flag      风险标识 code（如 FALL/PRESSURE），非空
     * @param updatedBy 回写操作者（评估流程操作者，审计留痕），非空
     * @return 影响行数（0=在册行不存在或已不含该标识，调用方重读定性）
     */
    @Update("UPDATE nursing.nursing_ward_patient "
            + "SET risk_flags = array_to_string(array_remove(string_to_array(risk_flags, ','), #{flag}), ','), "
            + "updated_by = #{updatedBy} "
            + "WHERE visit_id = #{visitId} AND deleted = 0 "
            + "AND position(',' || #{flag} || ',' in ',' || COALESCE(risk_flags, '') || ',') > 0")
    int casRemoveRiskFlag(
            @Param("visitId") String visitId, @Param("flag") String flag, @Param("updatedBy") String updatedBy);
}
