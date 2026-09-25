package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.DischargeRequest;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 出院申请 mapper（V907 discharge_request）：单表链式能力 + 状态/标记条件更新注解 SQL
 * （GC26：条件更新一律 @Update + 影响行数判定，显式补 deleted=0；状态字面量与 V907 列值域、
 * DischargeRequestStatus code 逐字同源）。结算完成标记与挂账审批放行两消费面（billing
 * 事件驱动）以 IS NULL/BLOCKED 限定 CAS 承载幂等——重复投递零行直返（监听器 info 留痕）。
 */
@Mapper
public interface DischargeRequestMapper extends BaseMapper<DischargeRequest> {

    /**
     * 结算完成标记 CAS（billing.settlement.completed 消费面）：在途申请行落
     * settlement_completed_at；IS NULL 限定兜底重复投递幂等（已标记零行直返）。
     *
     * @param requestNo 出院申请单号，非空；来源：载荷 visitId 反查的在途申请
     * @param settledAt 结算完成时点（载荷 occurredAt 回执时点），非空
     * @param operator  操作者（审计留痕，消费线程回退 system），非空
     * @return 影响行数（0=已标记幂等/无在途申请/已取消——正常场景，调用方仅记日志）
     */
    @Update("UPDATE inpatient.discharge_request SET settlement_completed_at = #{settledAt}, "
            + "updated_by = #{operator} WHERE request_no = #{requestNo} "
            + "AND settlement_completed_at IS NULL AND status IN ('REQUESTED','READY','BLOCKED') AND deleted = 0")
    int casMarkSettled(
            @Param("requestNo") String requestNo,
            @Param("settledAt") OffsetDateTime settledAt,
            @Param("operator") String operator);

    /**
     * 挂账审批放行 CAS（billing.arrears.approved 消费面）：BLOCKED→READY 并记录审批单号
     * （放行凭证留痕）；非 BLOCKED 态零行（已达 READY/终态幂等直返，调用方 info 留痕）。
     *
     * @param requestNo  出院申请单号，非空；来源：载荷 visitId 反查的在途申请
     * @param approvalNo 挂账审批单号（载荷 approvalNo——放行凭证），非空
     * @param operator   操作者（审计留痕，消费线程回退 system），非空
     * @return 影响行数（0=非 BLOCKED 态或行不存在——幂等/无在途申请正常场景）
     */
    @Update("UPDATE inpatient.discharge_request SET status = 'READY', approval_no = #{approvalNo}, "
            + "updated_by = #{operator} WHERE request_no = #{requestNo} AND status = 'BLOCKED' AND deleted = 0")
    int casApproveArrears(
            @Param("requestNo") String requestNo,
            @Param("approvalNo") String approvalNo,
            @Param("operator") String operator);

    /**
     * 离院完成 CAS（DischargeService.confirm 面）：READY→COMPLETED 终态；前置双条件
     * （READY+结算标记）已由服务层 GC19 三重校验裁决，本 CAS 兜底并发窗口。
     *
     * @param requestNo 出院申请单号，非空
     * @param operator  操作者（审计留痕），非空
     * @return 影响行数（0=非 READY 态并发迁移——调用方定性 IP-1023）
     */
    @Update("UPDATE inpatient.discharge_request SET status = 'COMPLETED', updated_by = #{operator} "
            + "WHERE request_no = #{requestNo} AND status = 'READY' AND deleted = 0")
    int casComplete(@Param("requestNo") String requestNo, @Param("operator") String operator);

    /**
     * 取消出院 CAS（DischargeService.cancel 面）：REQUESTED→CANCELLED 终态；仅申请中可取消
     * （READY/BLOCKED 态取消须先经业务裁决，Spec §5 状态机冻结边）。
     *
     * @param requestNo 出院申请单号，非空
     * @param operator  操作者（审计留痕），非空
     * @return 影响行数（0=非 REQUESTED 态——调用方定性 IP-1017）
     */
    @Update("UPDATE inpatient.discharge_request SET status = 'CANCELLED', updated_by = #{operator} "
            + "WHERE request_no = #{requestNo} AND status = 'REQUESTED' AND deleted = 0")
    int casCancel(@Param("requestNo") String requestNo, @Param("operator") String operator);
}
