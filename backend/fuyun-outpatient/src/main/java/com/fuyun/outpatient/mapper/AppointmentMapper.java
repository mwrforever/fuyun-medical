package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.Appointment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 预约/挂号单 mapper：单表操作（预约落库/业务号定位）经 BaseMapper 链式能力，另声明状态 CAS 两支
 * 注解 SQL——casStatus 供超时置 NO_SHOW 等状态迁移（Task 6 取消/改期复用），casTake 承载取号动作
 * （RESERVED→TAKEN + visit_id 回填 + 支付时限超时守卫，双道闸语义中 appointment 侧的先到先得闸）。
 * 条件更新一律 @Update + 影响行数判定（FeeRecordMapper.casMarkFeesSettled 实证形态）。必须标注
 * {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface AppointmentMapper extends BaseMapper<Appointment> {

    /**
     * 预约单状态 CAS：影响行数 0=行不存在/并发已迁移/状态违例（调用方重读定性后幂等跳过或拒绝）。
     * 超时置 NO_SHOW（Task 5 markTimeout）为首个消费点——「1 行才执行释放面」的业务态幂等守卫闸。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.ApptStatus} code 同源（入参传
     * {@code ApptStatus.XXX.getCode()}）；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）；
     * updated_by 固定 'system'（系统定性动作，无人工操作者语义）。
     *
     * @param id         预约单主键；来源：超时/取消载荷定位的预约行
     * @param fromStatus 期望迁出态 code（如 RESERVED）
     * @param toStatus   目标态 code（如 NO_SHOW）
     * @return 影响行数：1=迁移成功；0=行不存在或状态违例/并发落败（调用方重读定性）
     */
    @Update("UPDATE outpatient.appointment SET status = #{toStatus}, updated_by = 'system', updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casStatus(@Param("id") long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    /**
     * 取号 CAS（RESERVED→TAKEN + visit_id 回填单步原子）：支付时限超时守卫谓词
     * {@code pay_deadline IS NULL OR pay_deadline > now()}——窗口/自助当日挂号（pay_deadline 为空）
     * 与时限内取号放行，超时占位拒绝（调用方判 OP-1008）；与延迟任务超时置 NO_SHOW 并发时先到先得。
     *
     * <p>status/visit_id 字面量与 {@link com.fuyun.outpatient.enums.ApptStatus} code、CF-3 visit_id
     * 结构同源；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）；updated_by 固定 'system'。
     *
     * @param id      预约单主键；来源：取号请求按 appt_no 定位的预约行
     * @param visitId 签发的就诊号（O 型 14 位，CF-3）；来源：IVisitIdIssuer.issue()
     * @return 影响行数：1=取号成功（visit_id 已回填）；0=非 RESERVED/支付时限已过/行不存在
     */
    @Update("UPDATE outpatient.appointment SET status = 'TAKEN', visit_id = #{visitId}, updated_by = 'system', "
            + "updated_at = now() WHERE id = #{id} AND deleted = 0 AND status = 'RESERVED' "
            + "AND (pay_deadline IS NULL OR pay_deadline > now())")
    int casTake(@Param("id") long id, @Param("visitId") String visitId);

    /**
     * 挂号费退费终态 CAS（PAID→REFUNDED 条件迁移，Task 6 回执驱动）：仅已缴（PAID）行迁 REFUNDED
     * ——重复回执/费态漂移（非 PAID）0 行命中由调用方 warn 留痕不阻断（回执即终态权威，退号取消
     * 与回池先行完成）。
     *
     * <p>fee_status 字面量与 {@link com.fuyun.outpatient.enums.FeeStatusType} code 同源；
     * deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）；updated_by 固定 'system'（系统回执定性，
     * 操作者留痕经审计切面与 visit_status_log 承载）。
     *
     * @param id 预约单主键；来源：refund.approved 回执按 fee_settlement_id 定位的预约行
     * @return 影响行数：1=费态已迁 REFUNDED；0=非 PAID（重复回执/漂移）或行不存在
     */
    @Update("UPDATE outpatient.appointment SET fee_status = 'REFUNDED', updated_by = 'system', updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND fee_status = 'PAID'")
    int casMarkRefunded(@Param("id") long id);

    /**
     * 挂号费收费回填 CAS（UNPAID→PAID + 结算单 id 回填，Task 10 settlement.completed 消费侧）：
     * 挂号费与就诊费同 visit 结算（收费工作台直调 M13 面），结算回执经 visit 锚定位预约单后单条
     * 原子回填（PAID⇒visit 锚在位不变式——取号 casTake 已回填 visit_id，M13 结算面以 visit_id 为
     * NOT NULL 硬锚）。仅未缴行可迁，0 行=重投幂等/费态漂移，调用方重读定性。
     *
     * <p>fee_status 字面量与 {@link com.fuyun.outpatient.enums.FeeStatusType} code 同源；
     * deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）；updated_by 固定 'system'（系统回执定性，
     * 与 casMarkRefunded 同款口径）。
     *
     * @param id           预约单主键；来源：settlement.completed 载荷 visitId 定位的预约行
     * @param settlementId 结算单 id（fee_settlement_id 回填锚，退号退费定位依据）；来源：事件载荷
     * @return 影响行数：1=已回填 PAID+结算锚；0=非 UNPAID（重投/漂移）或行不存在
     */
    @Update("UPDATE outpatient.appointment SET fee_status = 'PAID', fee_settlement_id = #{settlementId}, "
            + "updated_by = 'system', updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND fee_status = 'UNPAID'")
    int casMarkPaid(@Param("id") long id, @Param("settlementId") long settlementId);
}
