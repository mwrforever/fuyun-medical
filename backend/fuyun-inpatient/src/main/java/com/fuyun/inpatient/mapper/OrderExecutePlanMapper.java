package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.OrderExecutePlan;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 住院医嘱执行计划 mapper（V906 order_execute_plan）：单表链式能力 + 计划作废/重定向/回签
 * 条件更新注解 SQL（GC26：条件更新一律 @Update + 影响行数判定，显式补 deleted=0；状态字面量
 * 与 V906 列值域、PlanStatus code 逐字同源）。执行回签 CAS（PENDING→EXECUTED，W-33 id 55
 * 契约）为本表状态迁移唯一执行面。
 */
@Mapper
public interface OrderExecutePlanMapper extends BaseMapper<OrderExecutePlan> {

    /**
     * 执行回签 CAS（W-33 id 55 契约 SQL 语义）：PENDING 限定更新至 EXECUTED 并落执行护士/
     * 执行时点/途径核对结论三引用列；0 行=已 EXECUTED（幂等，调用方返回当前状态）或已
     * CANCELLED（调用方拒 IP-1015）。
     *
     * @param planNo           计划号，非空
     * @param executorId       执行护士员工 ID（请求承载，executor_id 列落值），非空
     * @param executedAt       执行时点（请求缺省时=服务器时间），非空
     * @param routeCheckResult 给药途径核对结论（可空列落值，未携带为 null），可空
     * @param operator         操作者（审计留痕 updated_by，操作者上下文口径），非空
     * @return 影响行数（0=非 PENDING 态或行不存在）
     */
    @Update("UPDATE inpatient.order_execute_plan SET status = 'EXECUTED', executor_id = #{executorId}, "
            + "executed_at = #{executedAt}, route_check_result = #{routeCheckResult}, updated_by = #{operator} "
            + "WHERE plan_no = #{planNo} AND status = 'PENDING' AND deleted = 0")
    int casExecuteConfirm(
            @Param("planNo") String planNo,
            @Param("executorId") String executorId,
            @Param("executedAt") OffsetDateTime executedAt,
            @Param("routeCheckResult") String routeCheckResult,
            @Param("operator") String operator);

    /**
     * 停嘱联动未来计划批量作废（MedicalOrderServiceImpl.cancelFuturePlans 回接面——Task 5
     * 冻结调用点）：该医嘱停嘱时点后的 PENDING 计划批量置 CANCELLED；0 行=无未来计划
     * （正常场景，调用方仅记日志不定性冲突）。
     *
     * @param orderId   医嘱主键，非空
     * @param stoppedAt 停嘱时点（作废时间线基准——仅作废该时点后的未执行计划），非空
     * @param operator  操作者（审计留痕），非空
     * @return 影响行数（作废计划条数，0=无未来计划）
     */
    @Update("UPDATE inpatient.order_execute_plan SET status = 'CANCELLED', updated_by = #{operator} "
            + "WHERE order_id = #{orderId} AND status = 'PENDING' AND plan_time > #{stoppedAt} AND deleted = 0")
    int cancelFuturePending(
            @Param("orderId") Long orderId,
            @Param("stoppedAt") OffsetDateTime stoppedAt,
            @Param("operator") String operator);

    /**
     * 转科编排长期医嘱 PENDING 计划批量作废（计划三分钩子——长期计划作废面；停嘱联动仅覆盖
     * 未来时点，本面补齐长期医嘱全部在途 PENDING 计划的转科截断）。
     *
     * @param orderIds 长期医嘱主键集，非空（调用方保证非空集）
     * @param operator 操作者（审计留痕），非空
     * @return 影响行数（作废计划条数）
     */
    @Update("<script>UPDATE inpatient.order_execute_plan SET status = 'CANCELLED', updated_by = #{operator} "
            + "WHERE order_id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND status = 'PENDING' AND deleted = 0</script>")
    int cancelPendingByOrderIds(@Param("orderIds") List<Long> orderIds, @Param("operator") String operator);

    /**
     * 转科编排临时医嘱 PENDING 计划病区重定向（计划三分钩子——临时计划保留随患者面：
     * ward_id 批量改写至目标病区，仅限 PENDING——已执行计划归历史不重定向）。
     *
     * @param orderIds 临时医嘱主键集，非空（调用方保证非空集）
     * @param toWardId 目标病区编码，非空
     * @param operator 操作者（审计留痕），非空
     * @return 影响行数（重定向计划条数）
     */
    @Update("<script>UPDATE inpatient.order_execute_plan SET ward_id = #{toWardId}, updated_by = #{operator} "
            + "WHERE order_id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND status = 'PENDING' AND deleted = 0</script>")
    int redirectWardByOrderIds(
            @Param("orderIds") List<Long> orderIds,
            @Param("toWardId") String toWardId,
            @Param("operator") String operator);
}
