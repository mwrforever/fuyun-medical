package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.ArrearsApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 挂账审批 mapper：单表操作经 BaseMapper 链式能力，另声明审批决出原子语句（PENDING_APPROVAL
 * 唯一可决出边的 CAS 收口）。必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface ArrearsApprovalMapper extends BaseMapper<ArrearsApproval> {

    /**
     * 审批决出条件更新（通过/驳回共用 CAS，GC23 形态）：仅 PENDING_APPROVAL 行可决出，审批人/
     * 决定时间（库端 now()，禁应用时钟）/押金余额快照同语句落行（Task 12 casDecide 同族）——
     * 双审批人并发时后到者 0 行命中，由服务层拒 BILL-1033。status 字面量与
     * ArrearsApprovalStatus.code 同源；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param approvalNo      审批单号；来源：路径参数
     * @param targetStatus    目标状态（APPROVED/REJECTED）；来源：决出动作
     * @param approver        审批人（操作者上下文）；非空
     * @param approvedBalance 批准时点押金余额快照（分）；APPROVED 必填，REJECTED 传 null
     * @return 影响行数：1=决出成功；0=单号不存在/非待审批态/并发被抢（调用方按错误码语义拒）
     */
    @Update("UPDATE billing.arrears_approval SET status = #{targetStatus}, approver = #{approver}, "
            + "decided_at = now(), approved_balance = #{approvedBalance} "
            + "WHERE approval_no = #{approvalNo} AND status = 'PENDING_APPROVAL' AND deleted = 0")
    int casDecide(
            @Param("approvalNo") String approvalNo,
            @Param("targetStatus") String targetStatus,
            @Param("approver") String approver,
            @Param("approvedBalance") Long approvedBalance);
}
