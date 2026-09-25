package com.fuyun.inpatient.service.impl;

import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.service.OrderStateMachineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * 医嘱状态机服务实现（迁移表驱动，04-inpatient Spec §3.3 红线 2 执行面）：合法迁移表
 * （OrderStatus.canTransitionTo，13 条边硬编码于枚举）裁决 → CAS 条件更新（from 态限定，
 * 0 行定性并发迁移/终态违例）→ 迁移留痕。order_status_log 只增表归 Task 6 V905 落盘，
 * 本版本迁移留痕以结构化日志承载（appendStatusLog 调用点已就位，建表后同点回接落库，
 * 届时仅需覆写本方法注入 OrderStatusLogMapper——调用面零改动）。
 * 不发事件（接口契约冻结）：事件归调用方在同事务附带，避免状态机与业务面双写。
 * 线程安全：无状态 singleton；CAS 保并发迁移互斥。
 */
@Slf4j
public class OrderStateMachineServiceImpl implements OrderStateMachineService {

    private final MedicalOrderMapper orderMapper;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param orderMapper 医嘱主表 mapper，非空；状态 CAS 唯一执行通道
     */
    public OrderStateMachineServiceImpl(MedicalOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 医嘱状态迁移：迁移表裁决 → CAS → 留痕；迁移成功后内存行同步目标态（调用方零回读）。
     *
     * @param order    迁移目标医嘱行，非空
     * @param to       目标态，非空
     * @param reason   迁移原因（留痕面），非空
     * @param operator 操作者员工 ID，非空
     * @throws BizException IP-1010（409）迁移表外路径或 CAS 零行（并发迁移窗口）时触发
     */
    @Override
    public void transition(MedicalOrder order, OrderStatus to, String reason, Long operator) {
        OrderStatus from = OrderStatus.fromCode(order.getStatus());
        // 迁移表裁决（词表外 status 属主数据脏数据，与非法路径同拒——fail-closed）
        if (from == null || !from.canTransitionTo(to)) {
            log.warn(
                    "医嘱状态迁移被拒（合法迁移表外）：orderNo={}，{}→{}，operator={}",
                    order.getOrderNo(),
                    order.getStatus(),
                    to.getCode(),
                    operator);
            throw new BizException(
                    InpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "医嘱状态不允许该迁移：orderNo=" + order.getOrderNo() + "，" + order.getStatus() + "→" + to.getCode());
        }
        // 数据库写操作：状态 CAS（from 态限定更新；0 行=并发迁移窗口 he 方先迁，from 失配）
        int rows = orderMapper.casTransferStatus(
                order.getOrderNo(), from.getCode(), to.getCode(), String.valueOf(operator));
        if (rows == 0) {
            log.warn(
                    "医嘱状态迁移 CAS 零行（并发迁移窗口）：orderNo={}，期望 from={}，operator={}",
                    order.getOrderNo(),
                    from.getCode(),
                    operator);
            throw new BizException(
                    InpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "医嘱状态迁移并发冲突（他方先迁移）：orderNo=" + order.getOrderNo() + "，" + order.getStatus() + "→" + to.getCode());
        }
        // 内存行同步目标态（调用方后续值面补写/事件发布免回读）
        order.setStatus(to.getCode());
        // 迁移留痕（order_status_log 数据面归 Task 6 V905 建表后回接落库）
        appendStatusLog(order, from, to, reason, operator);
    }

    /**
     * 医嘱状态迁移留痕（order_status_log 只增写调用点）：order_status_log 表归 Task 6 V905
     * 建表落盘，本版本以结构化日志承载留痕面——建表后在本方法内补 OrderStatusLogMapper
     * 只增插入（调用点与签名冻结零改动）。protected 形态供 Task 6 回接时覆写扩展。
     *
     * @param order    迁移后医嘱行（status 已为目标态），非空
     * @param from     迁移前状态，非空
     * @param to       迁移后状态，非空
     * @param reason   迁移原因，非空
     * @param operator 操作者员工 ID，非空
     */
    // TODO(order-status-log): 迁移留痕落库，计划于 Task 6 V905 order_status_log 建表后回接
    protected void appendStatusLog(MedicalOrder order, OrderStatus from, OrderStatus to, String reason, Long operator) {
        log.info(
                "医嘱状态迁移：orderNo={}，{}→{}，reason={}，operator={}",
                order.getOrderNo(),
                from.getCode(),
                to.getCode(),
                reason,
                operator);
    }
}
