package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 生命体征记录实体（nursing.vital_sign_record，V803）：三源归一权威记录（方案 3.2）——
 * 手工（工作站）/PDA 点测 P1 直落 CONFIRMED，IoT 遥测归 P2；体温单权威栏只收 CONFIRMED 行。
 * (visit_id, measured_at, 体温部位) 唯一约束防双写（Spec :108）：部位经 DB 生成列 site_key
 * （COALESCE(temp_site,'')）承载——本实体不映射该生成列（GENERATED ALWAYS 禁写入，MP 插入
 * 列清单不得包含）。abnormal_flag 为录入时阈值判定结果快照（观察行归集判定依据，GC19 异常
 * 不被稀释的归集谓词列）。iot_quality/conflict_ref 列 P1 落位无写入方（GC17-① IoT 降级，P2 填）。
 */
@Getter
@Setter
@TableName("nursing.vital_sign_record")
public class VitalSignRecord {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 住院就诊号（I 型 14 位；在区校验经 IWardMetaService） */
    private String visitId;

    /** 患者主索引（在区行归一后服务端装配，不信客户端） */
    private Long patientId;

    /** 病区编码（在区行归一后服务端装配；待复核清单按病区检索键） */
    private String wardId;

    /** 测量时点（服务器时间，业务时间一律服务器时间红线） */
    private OffsetDateTime measuredAt;

    /** 体温（℃） */
    private BigDecimal temperature;

    /** 体温部位（TempSite code：ORAL 口温 / AXILLARY 腋温 / RECTAL 肛温；未测体温为空） */
    private String tempSite;

    /** 脉搏（次/分） */
    private Integer pulse;

    /** 呼吸（次/分） */
    private Integer respiration;

    /** 收缩压（mmHg） */
    private Integer systolicBp;

    /** 舒张压（mmHg） */
    private Integer diastolicBp;

    /** 血氧饱和度（%） */
    private Integer spo2;

    /** 体重（kg；不参与阈值判定） */
    private BigDecimal weight;

    /** 身高（cm；不参与阈值判定） */
    private BigDecimal height;

    /** 疼痛评分（NRS 0-10） */
    private Integer painScore;

    /** 数据源（VitalSource code：MANUAL/PDA/IOT） */
    private String source;

    /** 复核状态（VitalReviewStatus code：PENDING_REVIEW/CONFIRMED/REJECTED） */
    private String reviewStatus;

    /** 复核人（录入即 CONFIRMED 时为录入操作者） */
    private String reviewedBy;

    /** 复核时间（录入即 CONFIRMED 时为录入时点） */
    private OffsetDateTime reviewedAt;

    /** 是否越正常范围（阈值判定结果快照；观察行归集异常/合并判定依据） */
    private Boolean abnormalFlag;

    /** IoT 质量标记（GOOD/SUSPECT/BAD；P2 写入方，P1 恒空） */
    private String iotQuality;

    /** 同窗冲突对参照记录（P2 写入方，P1 恒空） */
    private Long conflictRef;

    /** 备注（驳回原因等） */
    private String remark;

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
