package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 医嘱执行单实体（nursing.order_execution，V1106）：住院医嘱执行载体——三路生成（转抄临时/
 * 计划批量/摆药挂接）、五环节状态链（ExecutionStatus）与双路回签对账的落点。生成域（Task 4）
 * 落三路中的前两路：exec_item 编码/名称为转抄快照占位（itemCode=医嘱号、itemName=医嘱类型子键），
 * 真实项目明细在摆药签收衔接（Task 6）从 dispense 行回填；输液类执行类型默认 GENERIC，回填时
 * 按用法升格 INFUSION。ward_id/bed_no 自病区患者投影（nursing_ward_patient）回填，转科随事件
 * 重定向（ward 变更、计划时间不变）。
 */
@Getter
@Setter
@TableName("nursing.order_execution")
public class OrderExecution {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 执行单号（EX+yyyyMMdd+5 位流水，本模块 NursingSeqGate 签发；uk_execution_no 唯一） */
    private String executionNo;

    /** M04 医嘱号（生成来源医嘱引用；uk_execution_plan 首要素） */
    private String m04OrderNo;

    /** M04 医嘱计划号（长期医嘱计划拆分引用；临时医嘱单次执行单为 NULL——类型快照行同 NULL 不受 uk 约束） */
    private String m04PlanNo;

    /** 住院就诊号（I 型 14 位，签发主体 M04） */
    private String visitId;

    /** 患者主索引 */
    private Long patientId;

    /** 病区编码（生成时自病区患者投影回填；转科随事件重定向） */
    private String wardId;

    /** 床位号（冗余展示，可空；转床随事件重定向——转科载荷仅携床位 id，文本形态承载） */
    private String bedNo;

    /** 执行类型（ExecutionType code：GENERIC 通用给药 / INFUSION 输液；生成默认 GENERIC，回填时升格） */
    private String executionType;

    /** 执行项目编码（生成时为医嘱号占位；摆药签收衔接时从 dispense 行回填真实项目编码） */
    private String execItemCode;

    /** 执行项目名称（生成时为转抄类型子键快照——计划生成的类型过滤依据；回填时替换真实项目名） */
    private String execItemName;

    /** 用法用量文本（医嘱转抄快照，执行提示用，可空；回填时补齐） */
    private String dosageText;

    /** 计划执行时间（逾期判定与时间窗排序基准；转科重定向不动本列） */
    private OffsetDateTime planTime;

    /** 执行单状态（ExecutionStatus code 六态；生成默认 CREATED） */
    private String status;

    /** 签收时点（环节时点集；签收动作落值） */
    private OffsetDateTime signedAt;

    /** 核对通过时点（环节时点集；扫码核对 PASS 落值） */
    private OffsetDateTime checkedAt;

    /** 开始执行时点（环节时点集；输液类=开始输注） */
    private OffsetDateTime startedAt;

    /** 执行完成时点（环节时点集；输液类=输注结束） */
    private OffsetDateTime finishedAt;

    /** 拔针时点（输液类专属环节时点；通用类为 NULL） */
    private OffsetDateTime needleOutAt;

    /** 执行护士员工 ID（签收/执行动作主体；生成时未定为 NULL） */
    private Long executorId;

    /** 核对护士员工 ID（扫码核对动作主体） */
    private Long checkerId;

    /** 破码放行标识（true=经授权破码跳过常规核对） */
    private Boolean overrideFlag;

    /** 撤销原因（停嘱/作废/出院清理联动撤销时必填留痕） */
    private String cancelReason;

    /** 回签对账状态（PENDING 待对账 / COMPENSATING 补偿中 / CONFIRMED 已对账；生成默认 PENDING） */
    private String confirmStatus;

    /** 升级次数（逾期升级动作递增，与任务表口径同源） */
    private Integer escalationCount;

    /** 最新关联告警号（IoT 输液告急挂接锚，M14 告警号） */
    private String latestAlarmNo;

    /** 创建时刻（DB now() 默认） */
    private OffsetDateTime createdAt;

    /** 更新时刻（DB now() 默认 + 触发器维护） */
    private OffsetDateTime updatedAt;

    /** 创建者（消费链路 system 回退口径） */
    private String createdBy;

    /** 更新者（消费链路 system 回退口径） */
    private String updatedBy;

    /** 逻辑删标记 */
    @TableLogic
    private Integer deleted;
}
