package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotAlarmEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 告警行 mapper：告警生命周期写面的 CAS 唯一入口（GC23 形态：状态迁移与聚合计数一律
 * {@code @Update} + 影响行数判定，显式补 deleted=0——并发双写以旧值限定兜底）。
 *
 * <p>四条 CAS：抑制①同源聚合（incrementTriggerIfActive）、抑制⑤升级防重发（casEscalate）、
 * 确认（casAcknowledge）、关闭（casClose）；一条扫描读：抑制⑤升级惰性扫描候选
 * （selectCriticalActive，条件内嵌 deleted=0）。必须标注 {@code @Mapper}：app 侧
 * MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotAlarmMapper extends BaseMapper<IotAlarmEntity> {

    /**
     * 抑制①同源聚合 CAS：同规则同设备的活跃行触发计数+1 并刷新最近触发时刻。
     *
     * @param ruleId   规则 ID，非空；来源：命中规则
     * @param deviceId 设备号，非空；来源：触发源设备
     * @param operator 操作者（引擎路径固定 system 审计留痕），非空
     * @return 影响行数（1=命中活跃行已聚合，调用方不新发；0=无活跃行，调用方可新发——
     *         并发新发窗口由部分唯一索引 uk_iot_alarm_active 物理兜底）
     */
    @Update("UPDATE iot.iot_alarm SET trigger_count = trigger_count + 1, last_triggered_at = now(), "
            + "updated_by = #{operator} "
            + "WHERE rule_id = #{ruleId} AND device_id = #{deviceId} AND status = 'ACTIVE' AND deleted = 0")
    int incrementTriggerIfActive(
            @Param("ruleId") long ruleId, @Param("deviceId") String deviceId, @Param("operator") String operator);

    /**
     * 抑制⑤升级 CAS：升级次数+1 并记最近升级时刻（旧值限定兜底并发双读——仅首个升级方发布事件，
     * DB escalation_count 防重发，inpatient 会诊逾期标记同款形态）。
     *
     * @param id            告警行 id，非空；来源：升级扫描候选行
     * @param expectedCount 期望的当前升级次数（读时快照旧值），非空
     * @param operator      操作者（引擎路径固定 system 审计留痕），非空
     * @return 影响行数（1=本次升级方，调用方发布 escalated 事件；0=并发已升级/状态已迁移不重发）
     */
    @Update("UPDATE iot.iot_alarm SET escalation_count = escalation_count + 1, last_escalated_at = now(), "
            + "updated_by = #{operator} "
            + "WHERE id = #{id} AND escalation_count = #{expectedCount} AND status = 'ACTIVE' AND deleted = 0")
    int casEscalate(
            @Param("id") long id, @Param("expectedCount") int expectedCount, @Param("operator") String operator);

    /**
     * 确认 CAS：ACTIVE → ACKNOWLEDGED（仅活跃行可确认，已确认/已关闭幂等拒绝）。
     *
     * @param alarmNo  告警业务号，非空；来源：确认端点路径变量
     * @param operator 确认人（操作者上下文，无登录上下文回退 system），非空
     * @return 影响行数（1=确认成功；0=无活跃行——并发已确认/已关闭/记录不存在）
     */
    @Update("UPDATE iot.iot_alarm SET status = 'ACKNOWLEDGED', acknowledged_by = #{operator}, "
            + "acknowledged_at = now(), updated_by = #{operator} "
            + "WHERE alarm_no = #{alarmNo} AND status = 'ACTIVE' AND deleted = 0")
    int casAcknowledge(@Param("alarmNo") String alarmNo, @Param("operator") String operator);

    /**
     * 关闭 CAS：ACTIVE/ACKNOWLEDGED → CLOSED（终态迁移，原因必填由服务层 @Valid 承担）。
     *
     * @param alarmNo 告警业务号，非空；来源：关闭端点路径变量
     * @param reason  关闭原因，非空；来源：操作者填写
     * @param operator 关闭人（操作者上下文，无登录上下文回退 system），非空
     * @return 影响行数（1=关闭成功；0=已关闭终态/记录不存在）
     */
    @Update("UPDATE iot.iot_alarm SET status = 'CLOSED', closed_by = #{operator}, closed_at = now(), "
            + "close_reason = #{reason}, updated_by = #{operator} "
            + "WHERE alarm_no = #{alarmNo} AND status IN ('ACTIVE','ACKNOWLEDGED') AND deleted = 0")
    int casClose(@Param("alarmNo") String alarmNo, @Param("reason") String reason, @Param("operator") String operator);

    /**
     * 抑制⑤升级惰性扫描候选：ACTIVE+CRITICAL 活跃告警行（idx_iot_alarm_escalation_scan 准入）。
     *
     * @return 升级候选清单（升级时限计算与规则装载由引擎完成），非空；无候选为空清单
     */
    @Select("SELECT * FROM iot.iot_alarm WHERE status = 'ACTIVE' AND alarm_level = 'CRITICAL' AND deleted = 0")
    List<IotAlarmEntity> selectCriticalActive();

    /**
     * 抑制③离线抑制查询：设备的全部活跃告警行（规则类型判定由 StormGuard 批量装载规则完成）。
     *
     * @param deviceId 设备号，非空；来源：触发源设备
     * @return 该设备活跃告警行清单，非空；无活跃行为空清单
     */
    @Select("SELECT * FROM iot.iot_alarm WHERE device_id = #{deviceId} AND status = 'ACTIVE' AND deleted = 0")
    List<IotAlarmEntity> selectActiveByDevice(@Param("deviceId") String deviceId);
}
