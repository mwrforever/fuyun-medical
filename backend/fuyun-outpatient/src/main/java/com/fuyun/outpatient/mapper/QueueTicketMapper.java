package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.QueueTicket;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 候诊票据 mapper：单表操作（建票/快照查询/票号定位）经 BaseMapper 链式能力，另声明状态 CAS 与
 * 惰性重建权威行读取注解 SQL——叫号/过号/重呼的状态数据面（Task 8 admit 消费 CALLED→SERVING）。
 * 必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface QueueTicketMapper extends BaseMapper<QueueTicket> {

    /**
     * 票据状态 CAS（过号 CALLED→PASSED/转队列放旧票→CANCELLED）：影响行数 0=行不存在/并发已迁移
     * （调用方重读定性后拒绝或跳过）。status 字面量与 {@link com.fuyun.outpatient.enums.TicketStatus}
     * code 同源（入参传 {@code TicketStatus.XXX.getCode()}）；deleted=0 显式补齐（注解 SQL 不继承
     * {@code @TableLogic}）；updated_by 携带操作者留痕（票据域无独立日志表，操作者落审计列）。
     *
     * @param id         票据主键（queue_ticket 表 PK）；来源：过号/转队列端点定位
     * @param fromStatus 期望迁出态 code（如 CALLED）
     * @param toStatus   目标态 code（如 PASSED）
     * @param operator   操作者标识（OperatorContextHolder），非空；留痕 updated_by
     * @return 影响行数：1=迁移成功；0=行不存在或并发落败
     */
    @Update("UPDATE outpatient.queue_ticket SET status = #{toStatus}, updated_by = #{operator}, updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casStatus(
            @Param("id") long id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("operator") String operator);

    /**
     * 叫号 CAS（call WAITING→CALLED 与 recall PASSED→CALLED 共用）：状态迁移+叫号计数累加+叫号
     * 时间回填单步原子。影响行数 0=行不存在/并发已迁移（调用方重读定性后判 OP-1013）。
     *
     * @param id         票据主键；来源：ZSET 出队所得 ticketPk
     * @param fromStatus 期望迁出态 code（WAITING 首叫/PASSED 重呼）
     * @param operator   操作者标识（叫号医生/分诊台），非空；留痕 updated_by
     * @return 影响行数：1=叫号成功（called_count 已累加、call_time 已回填）；0=并发落败或行不存在
     */
    @Update("UPDATE outpatient.queue_ticket SET status = 'CALLED', called_count = called_count + 1, "
            + "call_time = now(), updated_by = #{operator}, updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casCall(@Param("id") long id, @Param("fromStatus") String fromStatus, @Param("operator") String operator);

    /**
     * 队列当日 WAITING 权威行整体读取（叫号前置惰性重建数据源，Spec :210「叫号服务重启后队列从
     * 排队表完整恢复」）：queue_time 下界用库端 date_trunc（禁应用服务器时钟，防多实例漂移），
     * 隔日键过期后残留 WAITING 行不回灌新队列。
     *
     * @param deptCode 队列标识（=dept_code），非空
     * @return 当日 WAITING 票据行（queue_time 升序）；无在队票返回空列表
     */
    @Select("SELECT id, visit_id, queue_id, ticket_no, ticket_type, doctor_id, priority_score, queue_seq, "
            + "queue_time, called_count, call_time, serve_time, status "
            + "FROM outpatient.queue_ticket "
            + "WHERE queue_id = #{deptCode} AND deleted = 0 AND status = 'WAITING' "
            + "AND queue_time >= date_trunc('day', now()) ORDER BY queue_time")
    List<QueueTicket> selectWaiting(@Param("deptCode") String deptCode);
}
