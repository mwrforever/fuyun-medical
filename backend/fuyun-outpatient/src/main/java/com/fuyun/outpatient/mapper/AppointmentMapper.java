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
}
