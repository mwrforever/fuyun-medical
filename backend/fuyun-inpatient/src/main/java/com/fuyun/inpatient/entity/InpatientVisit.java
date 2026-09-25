package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院就诊实体（inpatient.inpatient_visit，V902）——就业主实体：入院登记确认（register）同事务
 * 签发 I 型 14 位 visit_id 并落 REGISTERED 行（M02 Spec 红线：I 型唯一签发主体 = 本模块，
 * visit_id 与签发时点归一 patient_id 同事务同时落库）；入科确认（admitWard）CAS 置 ADMITTED
 * 并登记当前科室/病区/床位/护理级别。visit_id 受 uk_visit_id 部分唯一约束兜底签发幂等。
 * 转科/转床仅变更 current_ward/current_bed 不改状态（后续任务承载）。
 */
@Getter
@Setter
@TableName("inpatient.inpatient_visit")
public class InpatientVisit {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联住院证 id（admission 1:0..1 inpatient_visit） */
    private Long admissionId;

    /** 住院就诊号（I+yyyyMMdd+5 位流水，定长 14 位，M02 结构规范；签发后不可变不可复用） */
    private String visitId;

    /** 患者主索引（签发时点归一主档，与 visit_id 同事务同时落库——M02 结论 ④） */
    private Long patientId;

    /** 当前科室编码（入科确认写入；转科变更） */
    private String currentDeptId;

    /** 当前病区编码（入科确认写入；转科/转床变更） */
    private String currentWardId;

    /** 当前床位 id（入科确认写入；bed 表归 V903/Task 4） */
    private Long currentBedId;

    /** 主治医生（入科确认写入） */
    private String attendingDoctorId;

    /** 护理级别（SPECIAL 特级 / CRITICAL 病重 / NORMAL 普通；权威在本表，M05 为视图镜像） */
    private String nursingLevel;

    /** 医保类型（险种标识，M01 字典 code；register 登记并随 inpatient.visit.registered 外发） */
    private String insuranceType;

    /** 入院诊断（register 自住院证誊写；敏感文本禁入事件载荷——脱敏红线） */
    private String admissionDiagnosis;

    /** 登记确认时点（visit_id 签发时点，应用服务器时钟） */
    private OffsetDateTime registeredAt;

    /** 入科确认时点（库端 now() 写入，禁应用时钟） */
    private OffsetDateTime admittedAt;

    /** 出院申请时点（Task 9 写入） */
    private OffsetDateTime dischargeRequestedAt;

    /** 出院完成时点（Task 9 写入） */
    private OffsetDateTime dischargedAt;

    /** 离院方式（病案首页代码，Task 9 写入） */
    private String dischargeWay;

    /** 欠费标识（Task 10 消费 billing.deposit.changed 刷新） */
    private Boolean arrearsFlag;

    /** 状态 code（VisitStatus：REGISTERED/ADMITTED/DISCHARGE_REQUESTED/DISCHARGED/CANCELLED） */
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
