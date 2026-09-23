package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 交接班实体（nursing.shift_handover，V807）：SBAR 结构化交接班落库载体——内容由系统按本班
 * 业务数据自动汇总生成草稿（患者摘要/待续事项 JSONB 快照 + SBAR 四段初稿文本），人工补充后
 * 双班签名 COMPLETED（双签同刻记录：交班签名=生成时刻、接班签名=完成时刻）。未完成不阻塞
 * 业务（无副作用）；待续事项的在途输注/未闭环告警引用随 M14/M16 接入（P2），P1 恒空数组。
 * JSONB 列以文本承载（pgjdbc getString 直读——NursingWardConfig/NursingAssessment 同款形态）。
 */
@Getter
@Setter
@TableName("nursing.shift_handover")
public class ShiftHandover {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 交接班单号（HO+yyyyMMdd+5 位流水，发号器统一取号；uk_shift_handover_no 唯一） */
    private String handoverNo;

    /** 病区编码 */
    private String wardId;

    /** 班次 code（取病区班次定义） */
    private String shiftCode;

    /** 交接班日期（列表按日检索） */
    private LocalDate handoverDate;

    /** 交班护士 */
    private String outgoingNurseId;

    /** 接班护士（完成签署时写入） */
    private String incomingNurseId;

    /** 患者摘要快照（JSONB 文本：{total,specialCount,criticalCount,newAdmissionCount,surgeryCount,todayDischargeCount,transferOutCount}） */
    private String patientSummary;

    /** S 现状（自动汇总初稿 + 人工补充） */
    private String sbarSituation;

    /** B 背景（自动汇总初稿 + 人工补充） */
    private String sbarBackground;

    /** A 评估（自动汇总初稿 + 人工补充） */
    private String sbarAssessment;

    /** R 建议（自动汇总初稿 + 人工补充） */
    private String sbarRecommendation;

    /** 待续事项：在途任务清单（JSONB 文本：[{taskNo,taskType,planTime,overdueFlag,visitId}]） */
    private String pendingItems;

    /** 待续事项：在途输注（JSONB 文本，P2 随 M14 接入，P1 恒 '[]'） */
    private String pendingInfusions;

    /** 待续事项：未闭环告警（JSONB 文本，P2 随 M16 接入，P1 恒 '[]'） */
    private String unclosedAlarms;

    /** 交班签名时间（生成时刻盖章） */
    private OffsetDateTime outgoingSignedAt;

    /** 接班签名时间（完成时刻盖章） */
    private OffsetDateTime incomingSignedAt;

    /** 交接班状态（HandoverStatus code：DRAFT/SIGNING/COMPLETED；SIGNING 为 P1 声明态） */
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
