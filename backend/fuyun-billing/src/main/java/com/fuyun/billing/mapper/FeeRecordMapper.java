package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.record.DailyListRow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 费用明细 mapper：单表操作经 BaseMapper 链式能力，另声明一日清单大类聚合语句
 * （复杂聚合 SQL 走 mapper+XML，宪法 A.4.3-15）与两支并发收口原子语句（2026-09-18 用户裁决：
 * 结算 CAS 抢锚 / 退费申请行锁）。必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface FeeRecordMapper extends BaseMapper<FeeRecord> {

    /**
     * 一日清单大类汇总聚合（GROUP BY fee_category_snapshot + SUM，ORDER BY 保证大类唯一顺序
     * A.4.3-15/17）：谓词人群与明细查询（visit_id+billing_date）严格同口径——两侧一致是三层
     * 勾稽（明细合计=大类汇总合计=总额）成立的前提。XML {@code #{visitId}}/{@code #{date}}
     * 按名绑定，@Param 显式标注（对齐 CardAccountMapper.mutateBalance 形态，第 2 轮审查 P2-6）。
     *
     * @param visitId CF-3 住院就诊号，非空；来源：service 守卫后入参
     * @param date    清单计费日，非空；来源：前端日期选择
     * @return 大类聚合行列表（feeCategorySnapshot 升序唯一序）；无费用日返回空列表
     */
    List<DailyListRow> dailyListSummary(@Param("visitId") String visitId, @Param("date") LocalDate date);

    /**
     * 结算费用行集条件更新（与 SettlementMapper.casMarkSettled 同族 CAS）：仅迁移仍处 PENDING 的
     * 行并回填结算引用——双 DRAFT 单并发结算同批费用时，第二单在本语句的行锁上被首单提交串行化，
     * 首单已置 SETTLED 的行不满足 WHERE 条件（READ COMMITTED 对新行版本重评估），影响行数不足
     * 期望数由服务层抛 BILL-1016 整体回滚，杜绝同批费用双单双扣。status 字面量与 FeeStatus.code
     * 同源；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param settlementId 结算单 id（回填到费用行 settlement_id）；来源：CAS 抢锚成功的结算行主键
     * @param feeIds       纳入结算的费用行 id 集（服务层勾稽通过的 PENDING 行）；非空
     * @return 影响行数：=feeIds.size() 全量迁移成功；&lt;期望数即并发被抢，调用方须整体回滚
     */
    @Update("<script>UPDATE billing.fee_record SET status = 'SETTLED', settlement_id = #{settlementId} "
            + "WHERE status = 'PENDING' AND deleted = 0 AND id IN "
            + "<foreach collection='feeIds' item='feeId' open='(' separator=',' close=')'>#{feeId}</foreach>"
            + "</script>")
    int casMarkFeesSettled(@Param("settlementId") long settlementId, @Param("feeIds") List<Long> feeIds);

    /**
     * 发药完成占用回写（M06 dispense.completed 消费）：NONE→DISPENSED 条件迁移。
     * exec_occupy_status 字面量与 {@link com.fuyun.billing.enums.ExecOccupyStatus} code 同源；
     * deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param rxNo 处方号（=fee_record.source_ref，PRESCRIPTION_EFFECTIVE 通道）；来源：事件载荷
     * @return 影响行数（0=无 NONE 行/已迁移——幂等达成）
     */
    @Update("UPDATE billing.fee_record SET exec_occupy_status = 'DISPENSED' "
            + "WHERE source_ref = #{rxNo} AND trigger_point = 'PRESCRIPTION_EFFECTIVE' "
            + "AND exec_occupy_status = 'NONE' AND deleted = 0")
    int casMarkDispensed(@Param("rxNo") String rxNo);

    /**
     * 全额退药占用回退（M06 dispense.returned fullReturn 消费）：DISPENSED→NONE（退费硬前置解锁）。
     * exec_occupy_status 字面量与 {@link com.fuyun.billing.enums.ExecOccupyStatus} code 同源；
     * deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param rxNo 处方号；来源：事件载荷
     * @return 影响行数（0=无 DISPENSED 行/已回退——幂等达成）
     */
    @Update("UPDATE billing.fee_record SET exec_occupy_status = 'NONE' "
            + "WHERE source_ref = #{rxNo} AND trigger_point = 'PRESCRIPTION_EFFECTIVE' "
            + "AND exec_occupy_status = 'DISPENSED' AND deleted = 0")
    int casReleaseDispense(@Param("rxNo") String rxNo);

    /**
     * 出院费用预审未结清合计（BillingAccountQueryPort.precheck 聚合权威）：PENDING+CONFIRMED
     * 且未结算（settlement_id IS NULL）费用行金额求和——作废/已结算/退费终态行不构成未结清。
     * status 字面量与 FeeStatus.code 同源；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param visitId CF-3 住院就诊号，非空；来源：M04 出院申请就诊行
     * @return 未结清费用合计（分）；无费用行返回 0
     */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM billing.fee_record "
            + "WHERE visit_id = #{visitId} AND status IN ('PENDING', 'CONFIRMED') "
            + "AND settlement_id IS NULL AND deleted = 0")
    long sumUnsettledAmount(@Param("visitId") String visitId);

    /**
     * 医嘱执行确认（M13 住院联动：inpatient.order.executed 消费）：该医嘱全部 PENDING 行
     * PENDING→CONFIRMED——执行回签是住院费用入账权威时点；消费侧直改状态不发事件
     * （billing.fee.confirmed 常量无调用点保持，Task 13 brief 冻结语义）。status 字面量与
     * FeeStatus.code 同源；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param m04OrderNo 医嘱号（=fee_record.source_ref，ORDER_CONFIRMED 通道）；来源：事件载荷
     * @param visitId    住院就诊号（同就诊限定，防跨就诊同号误伤）；来源：事件载荷
     * @return 影响行数（0=无 PENDING 行/重复投递已确认——幂等达成）
     */
    @Update("UPDATE billing.fee_record SET status = 'CONFIRMED' "
            + "WHERE source_ref = #{m04OrderNo} AND visit_id = #{visitId} "
            + "AND status = 'PENDING' AND settlement_id IS NULL AND deleted = 0")
    int casConfirmByOrder(@Param("m04OrderNo") String m04OrderNo, @Param("visitId") String visitId);

    /**
     * 停嘱费用截断（M13 住院联动：inpatient.order.stopped 消费）：该医嘱未确认 PENDING 行
     * PENDING→CANCELLED（已确认/已结算行不回冲——停嘱只截断在途费用）；部分唯一索引对
     * CANCELLED 行不占键，同医嘱同项目当日可重开重计。status 字面量与 FeeStatus.code 同源；
     * deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param m04OrderNo 医嘱号（=fee_record.source_ref）；来源：事件载荷
     * @param visitId    住院就诊号（同就诊限定）；来源：事件载荷
     * @return 影响行数（0=无在途 PENDING 行/重复投递已作废——幂等达成）
     */
    @Update("UPDATE billing.fee_record SET status = 'CANCELLED' "
            + "WHERE source_ref = #{m04OrderNo} AND visit_id = #{visitId} "
            + "AND status = 'PENDING' AND settlement_id IS NULL AND deleted = 0")
    int casCancelPendingByOrder(@Param("m04OrderNo") String m04OrderNo, @Param("visitId") String visitId);

    /**
     * 按主键集加行锁读回（SELECT ... FOR UPDATE，退费申请并发收口）：退费 apply 在校验前先锁
     * 目标费用行至事务提交——并发双申请同费用行时后到者在本语句阻塞，持锁者提交（负向 link 已落）
     * 后读到最新行与最新已退聚合，超可退守卫即拒，根除「双读 refundedFen 聚合互不可见」的
     * TOCTOU 全自动重复退款。ORDER BY id 统一锁序，防多行申请交叉加锁死锁。
     *
     * @param ids 退费明细引用的费用行 id 集（apply 去重后）；非空
     * @return 锁内读回的费用行（最新已提交版本，含 id 升序）；id 缺失即费用行不存在
     */
    @Select("<script>SELECT * FROM billing.fee_record WHERE deleted = 0 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY id FOR UPDATE</script>")
    List<FeeRecord> lockByIds(@Param("ids") List<Long> ids);
}
