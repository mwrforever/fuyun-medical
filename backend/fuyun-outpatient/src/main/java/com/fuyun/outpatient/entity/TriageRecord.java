package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.outpatient.enums.TriageAction;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 分诊动作留痕实体（outpatient.triage_record，M03 分诊台四类动作全留痕）：只增语义——零更新
 * （业务纠错以新动作行表达），priority_factor 为急/老幼残/回诊因子 JSON。线程安全：可变实体仅
 * service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.triage_record")
public class TriageRecord {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 就诊号（uk_visit_id 同源） */
    private String visitId;

    /** 分诊台/自助终端标识（报到发起端；非报到动作取分诊台缺省标识） */
    private String stationId;

    /** 分诊动作（TriageAction：CHECK_IN/RE_TRIAGE/LEVEL_ADJUST/QUEUE_TRANSFER） */
    private TriageAction action;

    /** 急诊分级快照（Ⅰ~Ⅳ=1~4，动作时刻值），可空 */
    private Integer triageLevel;

    /** 动作目标队列（=dept_code 诊区队列） */
    private String targetQueue;

    /** 二次分诊定医生（RE_TRIAGE 动作写入），可空 */
    private String doctorId;

    /** 优先级因子 JSON（如 ["ELDERLY"]，词表 ELDERLY/CHILD/DISABLED），可空 */
    private String priorityFactor;

    /** 分诊护士操作者（OperatorContextHolder 注入） */
    private String nurseId;

    /** 动作理由（调级/转队列留痕），可空 */
    private String reason;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列：操作人应用层注入 */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
