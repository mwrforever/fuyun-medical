package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.NursingRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 护理记录单 mapper：单表链式能力 + 条件更新注解 SQL 全集（GC26：条件更新一律 @Update +
 * 影响行数判定，显式补 deleted=0）。状态字面量与 V802 列值域、RecordStatus code 逐字同源；
 * 全部 @Update 均单表单语句、零级联。
 */
@Mapper
public interface NursingRecordMapper extends BaseMapper<NursingRecord> {

    /**
     * 提交锁定 CAS（DRAFT→SUBMITTED + 签名盖章）：仅 DRAFT 行可提交，正文提交后锁定
     * （GC25 护理文书红线）；签名操作者/签名时间随 CAS 一并落列（P1 有效留痕，CA 引用 P2 接）。
     *
     * @param recordNo       护理记录号，非空
     * @param signedOperator 签名操作者（OperatorContextHolder 当前操作者），非空
     * @return 影响行数（0=记录不存在或已提交/已修订，调用方定性 NS-1007）
     */
    @Update("UPDATE nursing.nursing_record SET status = 'SUBMITTED', signed_operator = #{signedOperator}, "
            + "signed_at = now() WHERE record_no = #{recordNo} AND status = 'DRAFT' AND deleted = 0")
    int casSubmit(@Param("recordNo") String recordNo, @Param("signedOperator") String signedOperator);

    /**
     * 观察行归集追加（appendObservation 合并分支消费体）：向当日既有自动观察行追加一段观察内容
     * （换行拼接；COALESCE 兜底历史行 observation 为 NULL 的极端数据，保证追加内容不丢失）。
     *
     * @param id        既有观察行 id（服务层先行定位），非空
     * @param content   追加的观察内容（Task 5 体征归集文本），非空
     * @param updatedBy 归集操作者（审计留痕），非空
     * @return 影响行数（0=观察行不存在或已被逻辑删，调用方定性）
     */
    @Update("UPDATE nursing.nursing_record SET observation = COALESCE(observation, '') || E'\\n' || #{content}, "
            + "updated_by = #{updatedBy} WHERE id = #{id} AND deleted = 0")
    int appendObservation(@Param("id") long id, @Param("content") String content, @Param("updatedBy") String updatedBy);
}
