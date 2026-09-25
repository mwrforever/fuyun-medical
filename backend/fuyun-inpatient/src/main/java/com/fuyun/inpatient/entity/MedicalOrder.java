package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院医嘱主实体（inpatient.medical_order，V904）——医嘱开立域主体：开立即落 CREATED
 * （待审核；用药类此期语义=待药师审），状态迁移唯一经 OrderStateMachineService（八态合法
 * 迁移表驱动 + CAS + 影响行数判定，04 Spec 红线 2）。visit_id 为 inpatient_visit 主键引用
 * （转科停嘱查询键，非 I 型号——I 型号经就诊行关联取）；成组医嘱多行共用 group_no（单条
 * 医嘱 group_no 缺省=order_no）；standby_flag 嘱托标记仅 LONG 可 true（应用层四层校验守卫）。
 */
@Getter
@Setter
@TableName("inpatient.medical_order")
public class MedicalOrder {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 医嘱号（MO+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("MO") 签发；uk 唯一） */
    private String orderNo;

    /** 住院就诊主键（inpatient_visit.id 非 I 型号；转科批量停嘱查询键） */
    private Long visitId;

    /** 患者主索引（开立时点就诊行权威值，事件载荷同源） */
    private Long patientId;

    /** 医嘱类型（OrderType 九值：DRUG/LAB/EXAM/SURGERY/BLOOD/NURSING/DIET/CONSULT/DISCHARGE_MED） */
    private String orderType;

    /** 医嘱分类（OrderClass 两值：LONG 长期/STAT 临时） */
    private String orderClass;

    /** 备用嘱（嘱托）标记：仅 LONG 可 true（STAT+standby 开立即拒 IP-1022） */
    private Boolean standbyFlag;

    /** 成组医嘱组号（单条医嘱=order_no 缺省回填；成组多行共用） */
    private String groupNo;

    /** 频次编码（order_frequency.freq_code；长期医嘱非空、临时医嘱 null） */
    private String freqCode;

    /** 医嘱生效起始时点（审核通过面写入，开立时缺省 null） */
    private OffsetDateTime beginAt;

    /** 停嘱时点（STOPPED 迁移补写=服务器时间；未停为 null） */
    private OffsetDateTime endAt;

    /** 开立医生（M01 用户标识，与审计列口径统一） */
    private String doctorId;

    /** 开立时点（应用服务器时钟） */
    private OffsetDateTime orderedAt;

    /** 停嘱原因（转科固定文案「转科」/医生停嘱理由） */
    private String stopReason;

    /** 状态 code（OrderStatus 八态：CREATED/AUDITED/AUDIT_REJECTED/TRANSFERRED/EXECUTING/COMPLETED/CANCELLED/STOPPED） */
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
