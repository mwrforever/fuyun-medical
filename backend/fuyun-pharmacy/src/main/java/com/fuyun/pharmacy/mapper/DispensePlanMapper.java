package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.DispensePlan;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 住院摆药计划 mapper：单表链式能力 + 生成域 uk 幂等插入 + 摆药流状态机 CAS 条件更新（注解
 * SQL 显式补 deleted=0，照 DispenseMapper 形态；0 行=并发被抢/状态违例，调用方 PH-1024 定性
 * 拒绝）。deliver 配送交接为半步时间线更新（不迁移状态——CHECKED 态内置 issued_at）。
 * insertIgnoreOrderTimeConflict 为 uk_dispense_plan_order_time 部分唯一索引（(m04_order_no,
 * plan_time) WHERE deleted=0）配套的 ON CONFLICT DO NOTHING 幂等插入（nursing 侧
 * insertIgnorePlanConflict 同款先例）——并发窗口对端已落同键行时 0 行整行放弃不抛（Java 侧
 * catch DuplicateKeyException 在 PG 下会中止物理事务毒化后续语句，25P02 后一切 SQL 必败，
 * 故以语句内幂等承载）。
 */
@Mapper
public interface DispensePlanMapper extends BaseMapper<DispensePlan> {

    /**
     * 幂等插入（generate 逐计划落库）：撞 uk_dispense_plan_order_time 部分唯一索引即整行
     * 放弃（DO NOTHING），ON CONFLICT 谓词与 V1110 索引定义（(m04_order_no, plan_time)
     * WHERE deleted = 0）逐字咬合。id 由 MP 参数处理器按 ASSIGN_ID 雪花回填；created_at/
     * updated_at/created_by/updated_by/deleted 未列列走 DB 默认（与 MP insert 未置字段同
     * 语义）。
     *
     * @param row 待插入计划行（planNo/planTime/status 等业务列已由服务侧置值），非空
     * @return 影响行数：1=落库成功；0=同 (m04_order_no, plan_time) 在册行已存在（并发窗口幂等达成）
     */
    @Insert("INSERT INTO pharmacy.dispense_plan ("
            + "id, plan_no, m04_order_no, visit_id, patient_id, ward_id, plan_type, plan_time, "
            + "status, label_printed) VALUES ("
            + "#{id}, #{planNo}, #{m04OrderNo}, #{visitId}, #{patientId}, #{wardId}, #{planType}, "
            + "#{planTime}, #{status}, #{labelPrinted}) "
            + "ON CONFLICT (m04_order_no, plan_time) WHERE deleted = 0 DO NOTHING")
    int insertIgnoreOrderTimeConflict(DispensePlan row);

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
