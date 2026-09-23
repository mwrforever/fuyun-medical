package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 病区患者本地视图实体（nursing.nursing_ward_patient，V801）【临时（P1 过渡）】：Spec :145
 * 「订阅 M04 病区/床位事件维护本地视图」授权的落点，P1 由过渡通道 POST /api/v1/nursing/ward-patients
 * 写入，P2 由事件链替代（退役触发条件与双侧留痕见 V801 迁移头注）。在区态受双唯一约束
 * （uk_ward_patient_visit / uk_ward_patient_bed）；status 仅 IN_WARD/REMOVED 二值（GC38 语义锁，
 * 出院/转科/换床语义归 M04）；condition_tags/risk_flags 为展示镜像逗号分隔文本，非权威。
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

    /** 床位号（在区态病区内唯一） */
    private String bedNo;

    /** 患者主索引（MERGED 时收敛主档，CF-3 语义；merged/split 订阅维护） */
    private Long patientId;

    /** 住院就诊号（I 型 14 位，签发主体 M04；M05 仅结构校验，在区态全局唯一） */
    private String visitId;

    /** 患者展示名（P1 过渡通道由操作者录入，P2 事件链携带） */
    private String patientName;

    /** 性别 code（M01 字典） */
    private String gender;

    /** 年龄（岁） */
    private Integer age;

    /** 护理级别（NursingLevel code：SPECIAL/CRITICAL/NORMAL；权威在 M04，本表为视图属性） */
    private String nursingLevel;

    /** 病情状态标记（逗号分隔展示镜像：CRITICAL/SEVERE/NEW/SURGERY/DELIVERY；P1 无生产者） */
    private String conditionTags;

    /** 过敏标识（patient.health-summary.updated 订阅刷新；详情卡另经 AllergyChecker 实时嵌查） */
    private Boolean allergyFlag;

    /** 风险标识（逗号分隔：FALL/PRESSURE；Task 8 评估高危经 appendRiskFlag 回写） */
    private String riskFlags;

    /** 入区时间（登记时点服务器时间；补录入院时间属 ADT 写能力，禁） */
    private OffsetDateTime admittedAt;

    /** 视图状态（WardPatientStatus code：IN_WARD 在区 / REMOVED 已移出病区一览） */
    private String status;

    /** 行来源（WardPatientSource code：MANUAL P1 过渡通道 / EVENT P2 事件链） */
    private String source;

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
