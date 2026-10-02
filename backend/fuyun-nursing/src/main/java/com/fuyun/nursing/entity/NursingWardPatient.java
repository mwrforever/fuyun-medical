package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 病区患者投影实体（nursing.nursing_ward_patient，V801 建 / V1108 退役改造）：W-34 退役后为
 * <b>纯事件投影</b>——单一写入面=InpatientVisitEventListener 四路消费（admitted upsert /
 * transferred 归属更新 / discharged 逻辑删 / bed.changed 补床号），P1 过渡通道（POST
 * /ward-patients 登记与移出）已随本退役删除；status/source 两列已 DROP，在册语义由逻辑删
 * deleted=0 单独承载（uk_ward_patient_visit/uk_ward_patient_bed 均带 deleted=0 谓词）。
 * bed_no 为床号文本语义列（V800 载荷无床号的路由由 bed.changed 补齐，禁落床位 id 文本）；
 * patient_name/gender/age 展示镜像列事件载荷不携（脱敏红线）——详情卡经 patient api 嵌查；
 * condition_tags/risk_flags 为展示镜像逗号分隔文本，非权威。
 */
@Getter
@Setter
@TableName("nursing.nursing_ward_patient")
public class NursingWardPatient {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 病区编码 */
    private String wardId;

    /** 床位号（在册态病区内唯一；床号文本语义——admitted 落空占位、bed.changed 补齐） */
    private String bedNo;

    /** 患者主索引（MERGED 时收敛主档，CF-3 语义；merged/split 订阅维护） */
    private Long patientId;

    /** 住院就诊号（I 型 14 位，签发主体 M04；在册态全局唯一） */
    private String visitId;

    /** 患者展示名占位（事件载荷脱敏不携姓名，落空占位；详情卡经 patient api 嵌查出参） */
    private String patientName;

    /** 性别 code（M01 字典；事件载荷不携，P2 投影行不落值） */
    private String gender;

    /** 年龄（岁；事件载荷不携，P2 投影行不落值） */
    private Integer age;

    /** 护理级别（NursingLevel code：SPECIAL/CRITICAL/NORMAL；权威在 M04，本表为投影属性） */
    private String nursingLevel;

    /** 病情状态标记（逗号分隔展示镜像：CRITICAL/SEVERE/NEW/SURGERY/DELIVERY；W-34 后无写入方，列保留供交接班汇总读） */
    private String conditionTags;

    /** 过敏标识（patient.health-summary.updated 订阅刷新；详情卡另经 AllergyChecker 实时嵌查） */
    private Boolean allergyFlag;

    /** 风险标识（逗号分隔：FALL/PRESSURE；评估高危经 appendRiskFlag 回写） */
    private String riskFlags;

    /** 入区时间（admitted 事件载荷权威承载） */
    private OffsetDateTime admittedAt;

    /** 创建时刻 */
    private OffsetDateTime createdAt;

    /** 更新时刻 */
    private OffsetDateTime updatedAt;

    /** 创建者 */
    private String createdBy;

    /** 更新者 */
    private String updatedBy;

    /** 逻辑删标记（在册语义唯一载体：discharged 事件置 1，同 visitId 再入院重新 upsert） */
    @TableLogic
    private Integer deleted;
}
