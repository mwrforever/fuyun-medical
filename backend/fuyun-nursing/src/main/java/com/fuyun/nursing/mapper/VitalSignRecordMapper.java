package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.VitalSignRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 生命体征记录 mapper：单表链式能力 + 复核 CAS 条件更新注解 SQL（GC26：@Update + 影响行数
 * 判定 + 显式 deleted=0）。行写入主链为 insert（唯一约束冲突由服务层转 NS-1016 幂等拒绝）；
 * 复核流转正/驳回为仅有的状态变更面（单表单语句、零级联），并发重复复核由 CAS 行数判定兜底。
 */
@Mapper
public interface VitalSignRecordMapper extends BaseMapper<VitalSignRecord> {

    /**
     * 复核 CAS（仅 PENDING_REVIEW 可转正/驳回，0 行 → NS-1015）：目标状态由调用方传定
     * （CONFIRMED/REJECTED），复核人/复核时间随 CAS 盖章；remark 经 COALESCE 承载驳回原因
     * （转正传 null 保持原值不动——IoT 上下文备注不被清空，P2 复用本语句）。jdbcType=VARCHAR
     * 保证 null 入参在 PG 侧类型可推断。
     *
     * @param id           体征记录 id，非空
     * @param targetStatus 目标复核状态（CONFIRMED / REJECTED），非空
     * @param reviewedBy   复核人（OperatorContextHolder 当前操作者），非空
     * @param remark       驳回原因（reject 必填；confirm 传 null 保持原备注），可空
     * @return 影响行数（0=记录不存在、已转正/已驳回或已被逻辑删，调用方定性 NS-1015）
     */
    @Update("UPDATE nursing.vital_sign_record SET review_status = #{targetStatus}, reviewed_by = #{reviewedBy}, "
            + "reviewed_at = now(), remark = COALESCE(#{remark, jdbcType=VARCHAR}, remark), updated_by = #{reviewedBy} "
            + "WHERE id = #{id} AND review_status = 'PENDING_REVIEW' AND deleted = 0")
    int casReview(
            @Param("id") long id,
            @Param("targetStatus") String targetStatus,
            @Param("reviewedBy") String reviewedBy,
            @Param("remark") String remark);
}
