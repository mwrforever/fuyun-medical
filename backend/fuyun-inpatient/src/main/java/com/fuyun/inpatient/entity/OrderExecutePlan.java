package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院医嘱执行计划实体（inpatient.order_execute_plan，V906）——医嘱执行闭环的计划实例面
 * （M05 执行单对齐）：三源开立共用（临时医嘱转抄同步生成单次计划/长期医嘱日切分解/嘱托
 * 按需触发）。计划按明细行粒度开立（order_item_id 维度——执行回签与计价按项对齐）；
 * uk_plan_order_item_time（order_item_id+plan_time）为日切分解幂等兜底（同项同时点不重开
 * 计划——M13 不重复计价的计划侧对偶面）。ward_id 为生成时点患者所在病区，转科编排对临时
 * PENDING 计划重定向本列（长期 PENDING 作废）。executor_id/executed_at/route_check_result
 * 三列为执行回签引用（W-33 id 55 契约的存储落点，未回签均为 null）。
 */
@Getter
@Setter
@TableName("inpatient.order_execute_plan")
public class OrderExecutePlan {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 计划号（PL+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("PL") 签发；uk 唯一） */
    private String planNo;

    /** 所属医嘱主键（经医嘱行取 order_class 分野转科三分口径） */
    private Long orderId;

    /** 所属医嘱明细行主键（medical_order_item.id；计划按明细行粒度开立） */
    private Long orderItemId;

    /** 住院就诊主键（inpatient_visit.id；M05 执行单与转科重定向查询键） */
    private Long visitId;

    /** 执行病区编码（生成时点患者所在病区；转科时临时 PENDING 计划重定向本列） */
    private String wardId;

    /** 计划执行时点（临时单次=转抄时点+默认准备窗口；长期分解=频次时点；嘱托=触发时点+窗口） */
    private OffsetDateTime planTime;

    /** 班次三值词表（照 V801 病区班次定义先例）：DAY 白班/EVENING 小夜班/NIGHT 大夜班 */
    private String shift;

    /** 执行护士（员工 ID string；执行回签时落值，未执行为 null） */
    private String executorId;

    /** 执行时点（执行回签时落值；未执行为 null） */
    private OffsetDateTime executedAt;

    /** 给药途径核对结论（执行回签可选携带；未回签为 null） */
    private String routeCheckResult;

    /** 计划状态（PlanStatus 三值：PENDING 待执行/EXECUTED 已执行/CANCELLED 已作废） */
    private String status;

    /** 创建时刻 */
    private OffsetDateTime createdAt;

    /** 更新时刻 */
    private OffsetDateTime updatedAt;

    /** 创建者 */
    private String createdBy;

    /** 更新者 */
    private String updatedBy;

    /** 逻辑删标记 */
    @TableLogic
    private Integer deleted;
}
