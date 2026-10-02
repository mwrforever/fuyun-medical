package com.fuyun.inpatient.api;

/**
 * M04 执行回签端口（M05→M04 主路径进程内直调面，P2 PR-3 Task 5）：nursing 执行单终态
 * （完成/输注中断部分执行）事务提交后同步回签 M04 执行计划——不走 HTTP 自调（GC17 双路
 * 回签裁决：主路径=进程内端口直调，辅路径=order-execution.completed 事件对账）。
 *
 * <p>实现挂接：{@code OrderPlanServiceImpl} 兼实现本端口（委托既有
 * {@code IOrderPlanService.executeConfirm} 逻辑直通——计划 CAS + 医嘱头三态推进 +
 * executed 事件）。billing api 端口（BillingAccountQueryPort）同款 spring-modulith
 * api 面先例，消费方 fuyun-nursing 仅依赖本 api 包。
 *
 * <p>操作者上下文：REST 链路 ThreadLocal 自然透传（登录护士）；补偿 tick 链路经
 * {@code OperatorContextHolder.set("SYSTEM")} 桥接（inpatient 侧 SYSTEM 白名单放行，
 * 审计列落 SYSTEM 文本）。
 *
 * <p>线程安全：无状态端口；实现侧 @Transactional 单事务收口。
 */
public interface OrderExecutionConfirmPort {

    /**
     * 执行回签（计划 PENDING→EXECUTED + 医嘱头三态推进 + inpatient.order.executed 事件）。
     *
     * @param planNo  M04 计划号（回签定位键，uk 锚），非空；来源：nursing 执行单 m04_plan_no
     * @param request 回签请求（executorId 执行护士/executedAt 执行时点缺省北京钟面当前/
     *                routeCheckResult 给药途径核对结论可空），非空
     * @return 回签出参（planNo/m04OrderNo/orderStatus/planStatus——幂等路径返回当前状态），非空
     * @throws com.fuyun.common.exception.BizException IP-1014（404 计划不存在）/ IP-1015（409
     *                 计划非 PENDING 且非 EXECUTED 幂等态——CANCELLED 拒绝）/ IP-1022（400
     *                 操作者上下文缺失且非 SYSTEM 白名单）
     */
    ExecuteConfirmVO executeConfirm(String planNo, ExecuteConfirmRequest request);
}
