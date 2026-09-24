package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.RefundRequest;
import com.fuyun.billing.record.FeeRefundedFenRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 退费申请 mapper：单表操作经 BaseMapper 链式能力（无 XML，宪法 A.4.3-15），另声明退费执行
 * CAS 抢锚语句（W-16，与 SettlementMapper.casMarkSettled 同族）与两支可退余额聚合下推查询
 * （PERF-01，连表聚合 SQL 走 {@code resources/mapper/RefundRequestMapper.xml}，宪法 A.4.3-15）。
 * 必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface RefundRequestMapper extends BaseMapper<RefundRequest> {

    /**
     * 退费执行 CAS 抢锚（W-16，与 SettlementMapper.casMarkSettled 同族）：仅 APPROVED 可迁移——
     * 并发双发同退费单恰一赢，输家重读定性（已 EXECUTED 幂等直返/否则 BILL-1019）；
     * 动卡入账严格后置于锚抢占成功（结算收口同款时序）。原生 SQL 状态字面量与
     * {@code RefundStatus} code 同源（APPROVED/EXECUTED 恒等常量名）；deleted=0 显式补齐
     * （@TableLogic 仅自动作用于 wrapper，注解 SQL 不继承）。
     *
     * @param id 退费申请 id
     * @return 影响行数（0=非 APPROVED 并发被抢/状态违例）
     */
    @Update("UPDATE billing.refund_request SET status = 'EXECUTED' "
            + "WHERE id = #{id} AND status = 'APPROVED' AND deleted = 0")
    int casMarkExecuted(@Param("id") long id);

    /**
     * 批量单费用行累计<b>已决</b>退费金额聚合下推（PERF-01；XML {@code sumDecidedRefundedFenByFeeIds}）：
     * refund_fee_link JOIN refund_request 按 status IN (APPROVED, EXECUTED) 过滤后 SUM(refund_amount)、
     * GROUP BY fee_id。资金语义等价性：与旧实现「先全量捞已决退费单整行实体取 id、再集内对 link
     * 逐费用求和」（原 decidedRefundedFen 两步内存计算）结果完全一致——已决额不可逆（APPROVED 后
     * 驳回不可达、EXECUTED 资金已动），execute 判费用行终态（FULL_REFUND/PART_REFUND）唯一合法口径；
     * 在途（待审批）两态绝不混入——其可被驳回而驳回不回滚费用行，混入会把驳回额误标 FULL_REFUND
     * （W-17 口径漂移修复后的收口，SQL 守卫测试逐子句钉死）。两表逻辑删在 SQL 内显式过滤。
     *
     * @param feeIds 费用行 id 集（聚合分组键），非空；来源：apply 锁内同批明细 / execute 本单 link 集归并
     * @return 逐费用行已退聚合行（fee_id 升序唯一序）；无已退 link 的费用行不出现在结果中（调用侧按 0 兜底）
     */
    List<FeeRefundedFenRow> sumDecidedRefundedFenByFeeIds(@Param("feeIds") List<Long> feeIds);

    /**
     * 批量单费用行累计<b>在途</b>退费金额聚合下推（PERF-01；XML {@code sumInFlightRefundedFenByFeeIds}）：
     * refund_fee_link JOIN refund_request 按 status IN (PENDING_APPROVAL, PENDING_SECOND_APPROVAL)
     * 过滤后 SUM(refund_amount)、GROUP BY fee_id，{@code excludeRefundId} 非空时在 SQL 侧排除该在途单
     * 自身（W-17 排斥语义：防同单审批期自我占用误判——当前调用面自身要么未落库要么已决，排除位为
     * 防御性语义锁定）。在途额计入是 apply 侧「兄弟单在途占位不得再被超额申请通过」的守卫面；
     * execute 判费用行终态禁用本口径。资金语义等价性：与旧实现「全量捞在途退费单整行实体取 id、
     * 集内对 link 逐费用求和」结果完全一致。
     *
     * @param feeIds          费用行 id 集（聚合分组键），非空；来源：apply 锁内同批明细
     * @param excludeRefundId 在途聚合排除的自身单 id，可空（null=不排除，apply 调用面口径）
     * @return 逐费用行在途聚合行（fee_id 升序唯一序）；无在途 link 的费用行不出现在结果中（调用侧按 0 兜底）
     */
    List<FeeRefundedFenRow> sumInFlightRefundedFenByFeeIds(
            @Param("feeIds") List<Long> feeIds, @Param("excludeRefundId") Long excludeRefundId);
}
