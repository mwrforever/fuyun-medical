package com.fuyun.pharmacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 处方实体（pharmacy.prescription，V701）：处方主数据唯一权威源（模块红线 1），rx_no 由本模块签发。
 * 枚举字段以 String 承载 code（值域见 enums 包，写入侧由服务层校验），与 billing 实体同型。
 */
@Getter
@Setter
@TableName("pharmacy.prescription")
public class Prescription {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 处方号（R+yyyyMMdd+流水，本模块签发，uk 唯一） */
    private String rxNo;

    /** 处方类型（RxType code） */
    private String rxType;

    /** 患者主索引（M02） */
    private Long patientId;

    /** CF-3 门诊就诊号（O 型 14 位） */
    private String visitId;

    /** 开方医生（登录上下文操作者） */
    private String doctor;

    /** 开方科室 */
    private String deptCode;

    /** 诊断引用（M01 字典 code 逗号分隔） */
    private String diagnosisCodes;

    /** 处方类别（RxCategory code，随药品毒麻类别派生） */
    private String rxCategory;

    /** 皮试要求（请求显式 OR 任一明细药品需皮试） */
    private Boolean skinTestRequired;

    /** 处方来源（RxSource code） */
    private String rxSource;

    /** 源医嘱号（出院带药转换引用，PR-4 恒 NULL） */
    private String sourceOrderNo;

    /** 有效截止（当日有效，参数化随 P3；PR-4 不承载时效拦截） */
    private OffsetDateTime validUntil;

    /** 预检分级（ReviewLevel code，PR-4 恒 PASS） */
    private String reviewLevel;

    /** 处方状态（PrescriptionStatus code，十字状态机） */
    private String status;

    /** 作废原因（CANCELLED 必填） */
    private String cancelReason;

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
