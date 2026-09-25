package com.fuyun.inpatient.service;

import com.fuyun.inpatient.dto.ExecuteConfirmRequest;
import com.fuyun.inpatient.vo.ExecuteConfirmVO;
import com.fuyun.inpatient.vo.OrderTraceVO;
import java.time.LocalDate;

/**
 * 医嘱执行计划服务（FU-M04-06 下，Task 8 业务面）：长期医嘱日切批量分解 + 当日增量补偿 +
 * CF-6/W-33 执行回签实装 + 闭环追溯聚合。计划行状态迁移为计划域自有面（CAS 条件更新，
 * brief SQL 语义）；医嘱头状态迁移唯一经 {@link OrderStateMachineService}（GC17，自动写
 * order_status_log）；事件事务内发布 AFTER_COMMIT 出 fy.topic（GC8）。
 */
public interface OrderPlanService {

    /**
     * 日切批量分解：全院在院（ADMITTED）就诊下 TRANSFERRED/EXECUTING 长期医嘱 × 频次时点
     * 序列 → 指定日期（调用方传次日）执行计划行（明细行×时点粒度，批量生成）。分批提交
     * （每 500 医嘱一事务，可断点续跑——重跑经查前置 + uk_plan_order_item_time 唯一约束双
     * 幂等兜底）；分解失败（频次字典缺失等）不建异常清单表——warn 日志（含 order_no 与
     * 缺失原因，供次日夜内缓冲人工处理）+ 该医嘱计划行不生成（简化决策，偏差登记 Task 16）；
     * 生成行按医嘱逐条发布 inpatient.order-plan.generated（V800 id 43 载荷）。
     *
     * @param planDate 计划日期（日切调用=次日；任意日期重跑均幂等），非空；来源：日切任务
     * @return 本次新生成计划行数（幂等重跑为 0），非负
     */
    int decomposeNextDay(LocalDate planDate);

    /**
     * 当日增量补偿：新开长期医嘱审核+转抄后即时补生成当日剩余时点（now 之后）计划——与
     * Task 7 转抄链衔接（OrderTransferServiceImpl.transferCheck 长期医嘱分支调用，转抄事务
     * 内加入）；当日无剩余时点返回 0。频次字典缺失等异常仅 warn 不阻断转抄主链。
     *
     * @param orderNo 长期医嘱号，非空；来源：转抄链/运维补偿入口
     * @return 本次新生成计划行数（当日无剩余时点/字典缺失为 0），非负
     * @throws com.fuyun.common.exception.BizException IP-1009 医嘱不存在
     */
    int compensateToday(String orderNo);

    /**
     * 执行回签（CF-6/W-33 契约实装）：计划 PENDING→EXECUTED（CAS 条件更新，0 行=已
     * EXECUTED 幂等返回当前状态、不迁移不发事件；CANCELLED 拒 IP-1015）+ 医嘱头三态推进
     * （长期首个回签 TRANSFERRED→EXECUTING / 临时单次 TRANSFERRED→COMPLETED / 长期全部
     * 计划实例终态 EXECUTING→COMPLETED——唯一经状态机）+ 事务内发布
     * inpatient.order.executed（V800 id 47 载荷）。
     *
     * @param planNo 计划号（路径参数 {no}），非空；来源：POST /api/v1/inpatient/order-plans/{no}/execute-confirm
     * @param req    回签入参（executorId 必填/executedAt 可空缺省服务器时间/routeCheckResult 可空），非空
     * @return 回签出参（planNo/m04OrderNo/迁移后医嘱头状态/迁移后计划状态），非空
     * @throws com.fuyun.common.exception.BizException IP-1014 计划不存在/IP-1015 计划状态
     *                 不允许（已作废）/IP-1009 关联医嘱缺失/IP-1007 关联就诊缺失/IP-1022
     *                 操作者上下文缺失或非数字/IP-1010 状态机迁移违例
     */
    ExecuteConfirmVO executeConfirm(String planNo, ExecuteConfirmRequest req);

    /**
     * 闭环追溯视图：开立→审核（含药师）→转抄→各计划执行→停止全环节人/时/果一屏聚合
     * （order_status_log + order_audit + order_transfer_log + order_execute_plan 四源
     * 时间线按发生时点升序稳定排序）。
     *
     * @param orderNo 医嘱号（路径参数 {no}），非空；来源：GET /api/v1/inpatient/orders/{no}/trace
     * @return 追溯聚合出参（五环节时间线），非空
     * @throws com.fuyun.common.exception.BizException IP-1009 医嘱不存在/IP-1007 关联就诊缺失（数据不一致）
     */
    OrderTraceVO trace(String orderNo);
}
