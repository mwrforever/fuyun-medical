package com.fuyun.inpatient.vo;

import com.fuyun.inpatient.entity.OrderExecutePlan;
import java.time.OffsetDateTime;

/**
 * 执行计划出参（GET /api/v1/inpatient/order-plans 列表行与嘱托触发生成回执共用；实体禁直出
 * ——出网边界唯一出口）。字段面与 V906 order_execute_plan 冻结列面一一对应（orderNo/visitId
 * 为号映射出参形态——表内存医嘱主键与就诊主键，出参经关联行映射）。
 *
 * @param planNo           计划号（PL+yyyyMMdd+5 位流水），非空
 * @param orderNo          所属医嘱号，非空
 * @param visitId          住院就诊号（I 型 14 位），非空
 * @param wardId           执行病区编码，非空
 * @param planTime         计划执行时点，非空
 * @param shift            班次 code（DAY 白班/EVENING 小夜班/NIGHT 大夜班），非空
 * @param executorId       执行护士（员工 ID string；未回签为 null）
 * @param executedAt       执行时点（未回签为 null）
 * @param routeCheckResult 给药途径核对结论（未回签为 null）
 * @param status           计划状态 code（PlanStatus 三值：PENDING/EXECUTED/CANCELLED），非空
 */
public record OrderPlanVO(
        String planNo,
        String orderNo,
        String visitId,
        String wardId,
        OffsetDateTime planTime,
        String shift,
        String executorId,
        OffsetDateTime executedAt,
        String routeCheckResult,
        String status) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity  计划行，非空
     * @param orderNo 所属医嘱号（关联行号映射），非空
     * @param visitNo 住院就诊号（I 型 14 位，关联行号映射），非空
     * @return 出参，非空
     */
    public static OrderPlanVO from(OrderExecutePlan entity, String orderNo, String visitNo) {
        return new OrderPlanVO(
                entity.getPlanNo(),
                orderNo,
                visitNo,
                entity.getWardId(),
                entity.getPlanTime(),
                entity.getShift(),
                entity.getExecutorId(),
                entity.getExecutedAt(),
                entity.getRouteCheckResult(),
                entity.getStatus());
    }
}
