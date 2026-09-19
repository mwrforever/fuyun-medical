package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.Prescription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 处方 mapper：单表链式能力 + 状态机 CAS 条件更新族（退费/发药并发收口同款，FeeRecordMapper
 * casMarkFeesSettled 形态）。状态字面量与 PrescriptionStatus code 逐字同源；注解 SQL 显式补
 * deleted = 0（不继承 @TableLogic）。返回影响行数：0=并发被抢/状态违例，调用方重读定性
 * （幂等直返或 PH-1005/1009 拒绝）。
 */
@Mapper
public interface PrescriptionMapper extends BaseMapper<Prescription> {

    /**
     * 通用状态 CAS（从态命中才迁移）。
     *
     * @param id   处方 id
     * @param from 期望现态 code，非空
     * @param to   目标态 code，非空
     * @return 影响行数（0=未命中）
     */
    @Update("UPDATE pharmacy.prescription SET status = #{to} "
            + "WHERE id = #{id} AND status = #{from} AND deleted = 0")
    int casStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    /**
     * 预检通过级放行（CREATED→APPROVED 同事务第二步，Spec :132）。
     *
     * @param id 处方 id
     * @return 影响行数
     */
    @Update("UPDATE pharmacy.prescription SET status = 'APPROVED' "
            + "WHERE id = #{id} AND status = 'CREATED' AND deleted = 0")
    int casApprove(@Param("id") long id);

    /**
     * 未缴费作废（APPROVED/PENDING_FEE→CANCELLED + 原因留痕）。
     *
     * @param id     处方 id
     * @param reason 作废原因（必填，CANCELLED 留痕）
     * @return 影响行数（0=并发被抢先迁移）
     */
    @Update("UPDATE pharmacy.prescription SET status = 'CANCELLED', cancel_reason = #{reason} "
            + "WHERE id = #{id} AND status IN ('APPROVED', 'PENDING_FEE') AND deleted = 0")
    int casCancel(@Param("id") long id, @Param("reason") String reason);
}
