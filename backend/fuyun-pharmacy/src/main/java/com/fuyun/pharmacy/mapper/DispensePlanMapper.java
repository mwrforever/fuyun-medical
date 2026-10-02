package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.DispensePlan;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 住院摆药计划 mapper：单表链式能力 + 摆药流状态机 CAS 条件更新（注解 SQL 显式补 deleted=0，
 * 照 DispenseMapper 形态；0 行=并发被抢/状态违例，调用方 PH-1024 定性拒绝）。
 * deliver 配送交接为半步时间线更新（不迁移状态——CHECKED 态内置 issued_at）。
 */
@Mapper
public interface DispensePlanMapper extends BaseMapper<DispensePlan> {

    /**
     * 摆药开始 CAS（CREATED→PICKING + 摆药师留痕随行落值）。
     *
     * @param id       计划 id
     * @param pickedBy 摆药师员工 ID，非空
     * @return 影响行数（0=非 CREATED 并发被抢/状态违例）
     */
    @Update("UPDATE pharmacy.dispense_plan SET status = 'PICKING', picked_by = #{pickedBy} "
            + "WHERE id = #{id} AND status = 'CREATED' AND deleted = 0")
    int casPick(@Param("id") long id, @Param("pickedBy") long pickedBy);

    /**
     * 药师核对 CAS（PICKING→PICKED + 核对药师留痕；labelPrinted 参数承载 PIVAS 贴签核对置位——
     * 非 PIVAS 链传 false 维持原值，单语句避免二次写）。
     *
     * @param id           计划 id
     * @param verifiedBy   核对药师员工 ID，非空
     * @param labelPrinted 贴签核对标记（PIVAS=true / 非 PIVAS=false）
     * @return 影响行数（0=非 PICKING 并发被抢/状态违例）
     */
    @Update("UPDATE pharmacy.dispense_plan SET status = 'PICKED', verified_by = #{verifiedBy}, "
            + "label_printed = #{labelPrinted} WHERE id = #{id} AND status = 'PICKING' AND deleted = 0")
    int casVerify(
            @Param("id") long id, @Param("verifiedBy") long verifiedBy, @Param("labelPrinted") boolean labelPrinted);

    /**
     * 通用状态 CAS。
     *
     * @param id   计划 id
     * @param from 期望现态 code，非空
     * @param to   目标态 code，非空
     * @return 影响行数（0=未命中）
     */
    @Update("UPDATE pharmacy.dispense_plan SET status = #{to} "
            + "WHERE id = #{id} AND status = #{from} AND deleted = 0")
    int casStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    /**
     * 配送交接时间线半步（CHECKED 态内 issued_at 置位——不迁移状态；重复调用幂等覆盖时点）。
     *
     * @param id        计划 id
     * @param deliverAt 配送交接时点，非空
     * @return 影响行数（0=非 CHECKED 并发被抢）
     */
    @Update("UPDATE pharmacy.dispense_plan SET issued_at = #{deliverAt} "
            + "WHERE id = #{id} AND status = 'CHECKED' AND deleted = 0")
    int markDeliverHandover(@Param("id") long id, @Param("deliverAt") OffsetDateTime deliverAt);

    /**
     * 病区签收终笔 CAS（CHECKED→DELIVERED + 签收人/签收时点随行落值；issued_at IS NOT NULL
     * 谓词=未配送不可签收硬防线，服务层预判 PH-1026 定性、本语句兜底并发窗口）。
     *
     * @param id          计划 id
     * @param receivedBy  病区签收人员工 ID，非空
     * @param deliveredAt 病区签收时点，非空
     * @return 影响行数（0=非 CHECKED 被抢或未配送）
     */
    @Update("UPDATE pharmacy.dispense_plan SET status = 'DELIVERED', received_by = #{receivedBy}, "
            + "delivered_at = #{deliveredAt} "
            + "WHERE id = #{id} AND status = 'CHECKED' AND issued_at IS NOT NULL AND deleted = 0")
    int markDelivered(
            @Param("id") long id,
            @Param("receivedBy") long receivedBy,
            @Param("deliveredAt") OffsetDateTime deliveredAt);

    /**
     * 终清联动作废 CAS（CREATED/PICKING 两态批量谓词——已摆出（PICKED 及之后）不属未摆药面，
     * 出院场景经服务层另行提示人工退药，不作废）。
     *
     * @param id     计划 id
     * @param reason 作废原因（停嘱原因/出院终清留痕），非空
     * @return 影响行数（0=已终态/已摆出——消费幂等跳过面）
     */
    @Update("UPDATE pharmacy.dispense_plan SET status = 'CANCELLED', cancel_reason = #{reason} "
            + "WHERE id = #{id} AND status IN ('CREATED', 'PICKING') AND deleted = 0")
    int casCancel(@Param("id") long id, @Param("reason") String reason);
}
