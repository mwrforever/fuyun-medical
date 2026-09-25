package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 会诊单实体（inpatient.consultation，V908）——院内会诊申请/响应/意见/超时升级闭环主体：
 * 两条建单路径（POST /consultations 独立申请 + CONSULT 类医嘱审核钩子自动建草稿，order_ref
 * 关联医嘱号）；状态四值小状态机（REQUESTED/ACCEPTED/COMPLETED/CANCELLED），迁移唯一经
 * ConsultationMapper CAS 条件更新 + 影响行数判定（GC23）；超时升级不在状态机内——
 * overdue_flag 承载读时惰性逾期判定（置位+overdue 动作事件一次的防重发锚，接单清零），
 * 状态停留 REQUESTED 仍可被响应。会诊意见归档供 M09 病历引用（本 PR 零 M09 消费面）。
 */
@Getter
@Setter
@TableName("inpatient.consultation")
public class Consultation {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会诊单号（CS+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("CS") 签发；uk 兜底发号幂等） */
    private String consultNo;

    /** 住院就诊主键（inpatient_visit.id——与 medical_order.visit_id 同口径，禁 I 型号入库） */
    private Long visitId;

    /** 患者主索引（事件载荷取数面） */
    private Long patientId;

    /** 关联会诊医嘱号（CONSULT 类医嘱审核钩子自动建单落 order_no，审核重试幂等锚；独立申请为 null） */
    private String orderRef;

    /** 申请科室编码（M01 组织 code；独立申请与钩子草稿同源取就诊 current_dept_id，可空） */
    private String fromDeptId;

    /** 申请医生（员工 ID string；独立申请=操作者上下文，钩子草稿=医嘱开立医生） */
    private String requesterId;

    /** 受邀科室编码（M01 组织 code；独立申请必填，钩子草稿待受邀科接单面明确，可空） */
    private String toDeptId;

    /** 会诊级别（ConsultationLevel 三值：DEPT/HOSPITAL/MDT 预留） */
    private String level;

    /** 紧急程度（ConsultationUrgency 两值：URGENT 急会诊 30min / NORMAL 普通 24h） */
    private String urgency;

    /** 申请原因（cancelled 事件 reason 同源取本列；可空） */
    private String reason;

    /** 申请时点（应用服务器时钟） */
    private OffsetDateTime requestedAt;

    /** 响应截止时点（申请时点+响应时限；idx_consultation_deadline 逾期扫描面） */
    private OffsetDateTime responseDeadline;

    /** 受邀科接单时点（库端 now()，accept CAS 落值；未接单为 null） */
    private OffsetDateTime responseTime;

    /** 会诊完成时点（库端 now()，opinion CAS 落值；未完成为 null） */
    private OffsetDateTime consultTime;

    /** 会诊意见（ACCEPTED→COMPLETED 意见提交落值；归档供 M09 引用，未完成为 null） */
    private String opinion;

    /** 逾期升级标记（读时惰性判定置位+overdue 动作事件一次的防重发锚；接单清零） */
    private Boolean overdueFlag;

    /** 状态 code（ConsultationStatus 四值小状态机） */
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
