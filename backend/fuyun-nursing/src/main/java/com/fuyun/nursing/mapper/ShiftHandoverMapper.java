package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.ShiftHandover;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 交接班 mapper：单表链式能力 + 完成双签 CAS 条件更新注解 SQL（GC26：@Update + 影响行数判定 +
 * 显式 deleted=0）。行写入主链为 insert（唯一约束冲突由服务层转 NS-1016 幂等拒绝）；casComplete
 * 为仅有状态变更面（单表单语句、零级联），DRAFT/SIGNING 两态可完成，并发重复完成由 CAS 行数
 * 判定兜底（0 行 → NS-1013）；SBAR 四段空串保留初稿（COALESCE(NULLIF(..))——人工未补充时
 * 不覆盖自动汇总文本）。
 */
@Mapper
public interface ShiftHandoverMapper extends BaseMapper<ShiftHandover> {

    /**
     * 完成 CAS（DRAFT/SIGNING 可完成，0 行 → NS-1013）：置 COMPLETED 并盖章接班签名
     * （DB now()，与审计列同源时钟）+ 接班护士；SBAR 四段空串保留初稿；并发重复完成由
     * 行数判定兜底。
     *
     * @param handoverNo        交接班单业务号，非空
     * @param incomingNurseId   接班护士工号（完成签署方），非空
     * @param sbarSituation     S 现状补充文本，非空（空串=保留初稿，服务面已做 null 归一）
     * @param sbarBackground    B 背景补充文本，非空（空串=保留初稿）
     * @param sbarAssessment    A 评估补充文本，非空（空串=保留初稿）
     * @param sbarRecommendation R 建议补充文本，非空（空串=保留初稿）
     * @param operator          操作者（OperatorContextHolder 当前操作者），非空
     * @return 影响行数（0=单不存在、已 COMPLETED 或已被逻辑删，调用方定性 NS-1013）
     */
    @Update("UPDATE nursing.shift_handover SET status = 'COMPLETED', incoming_nurse_id = #{incomingNurseId}, "
            + "incoming_signed_at = now(), "
            + "sbar_situation = COALESCE(NULLIF(#{sbarSituation}, ''), sbar_situation), "
            + "sbar_background = COALESCE(NULLIF(#{sbarBackground}, ''), sbar_background), "
            + "sbar_assessment = COALESCE(NULLIF(#{sbarAssessment}, ''), sbar_assessment), "
            + "sbar_recommendation = COALESCE(NULLIF(#{sbarRecommendation}, ''), sbar_recommendation), "
            + "updated_by = #{operator} "
            + "WHERE handover_no = #{handoverNo} AND status IN ('DRAFT', 'SIGNING') AND deleted = 0")
    int casComplete(
            @Param("handoverNo") String handoverNo,
            @Param("incomingNurseId") String incomingNurseId,
            @Param("sbarSituation") String sbarSituation,
            @Param("sbarBackground") String sbarBackground,
            @Param("sbarAssessment") String sbarAssessment,
            @Param("sbarRecommendation") String sbarRecommendation,
            @Param("operator") String operator);
}
