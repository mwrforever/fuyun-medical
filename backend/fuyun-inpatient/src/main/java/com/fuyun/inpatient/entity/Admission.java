package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院证（入院申请）实体（inpatient.admission，V902）：待入院队列主体，登记即建单入 WAITING
 * 队列；schedule 预约置 SCHEDULED 并记录目标床位/预约日期（床位 RESERVED 预占联动调 BedService
 * 归 Task 4 随 V903 bed 落地后补齐）；register 登记确认 CAS 置 COMPLETED（终态）。
 * admission_no 受 uk_admission_no 部分唯一约束兜底。
 */
@Getter
@Setter
@TableName("inpatient.admission")
public class Admission {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 住院证号（AD+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("AD") 签发；逻辑删内在途唯一） */
    private String admissionNo;

    /** 患者主索引（经 PatientContextResolver 归一后落库，CF-3；登记确认时二次解析拦截 FROZEN） */
    private Long patientId;

    /** 来源 code（SourceType：OUTPATIENT 门诊转诊 / EMERGENCY 急诊 / PEIS 体检 / OTHER 其他） */
    private String sourceType;

    /** 门诊 visit_id 引用（O 型 14 位；source_type=OUTPATIENT 转诊关联，两 visit 各自独立） */
    private String sourceVisitId;

    /** 目标科室编码（M01 组织机构 code） */
    private String targetDeptId;

    /** 目标病区编码（schedule 预约写入） */
    private String targetWardId;

    /** 目标床位 id（schedule 预约写入；bed 表归 V903/Task 4，本列先承载引用值） */
    private Long targetBedId;

    /** 入院类型 code（AdmissionType：NORMAL/EMERGENCY/PRE_HOSPITAL；EMERGENCY 为队列排序第一优先键） */
    private String admissionType;

    /** 预约入院日期（队列排序第二键=预约时段；缺省 null 排后） */
    private LocalDate expectDate;

    /** 入院诊断摘要（register 时誊写至 inpatient_visit.admission_diagnosis；敏感文本禁入事件载荷） */
    private String diagnosisSummary;

    /** 开证医生（M01 用户标识，VARCHAR(64) 口径与审计列统一） */
    private String issuedDoctorId;

    /** 状态 code（AdmissionStatus：WAITING/SCHEDULED/COMPLETED/CANCELLED） */
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
