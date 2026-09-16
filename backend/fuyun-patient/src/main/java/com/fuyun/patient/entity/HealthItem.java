package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 健康档案明细项：纠错置旧行 CORRECTED 并新增回链行。
 *
 * <p>纠错不改原记录（FU-M02-05 留痕口径）：原行 status 置 CORRECTED 保留，纠正内容落新行、
 * correct_of_item_id 指向被纠错原行；item_code 引用 M01 字典 code 不自建副本。
 */
@Getter
@Setter
@TableName("patient.health_item")
public class HealthItem {

    /** 明细 id（雪花，MP ASSIGN_ID 插入时生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 患者主索引 */
    private Long patientId;

    /** 项类型：ALLERGY 过敏/CHRONIC 慢病/SURGERY 手术/VACCINATION 免疫接种（HealthItemType） */
    private String itemType;

    /** 过敏物/ICD 诊断/疫苗 code（引用 M01 字典，不自建副本；可空=手工项） */
    private String itemCode;

    /** 项目名称（录入原文，检索与展示主依据） */
    private String itemName;

    /** 严重程度（过敏项：MILD/MODERATE/SEVERE，可空） */
    private String severity;

    /** 发生日期 */
    private LocalDate onsetDate;

    /** 状态：ACTIVE 有效/CORRECTED 已纠错（纠错置旧行，HealthItemStatus） */
    private String status;

    /** 来源：DOCTOR_STATION 医生站录入/MANUAL 手工补录 */
    private String source;

    /** 备注（纠错行的纠错说明亦落本列） */
    private String note;

    /** 纠错链：本行是对该行的纠错重录（首录为空） */
    private Long correctOfItemId;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：默认 'system' */
    private String createdBy;

    /** 更新人：默认 'system' */
    private String updatedBy;

    /** 逻辑删除标记（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
