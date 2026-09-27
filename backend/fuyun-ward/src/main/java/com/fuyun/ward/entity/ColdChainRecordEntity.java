package com.fuyun.ward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.ward.enums.ColdChainRecordType;
import com.fuyun.ward.handler.JsonbTypeHandler;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 冷链记录实体（ward.cold_chain_record，V1101 迁移）：巡检/告警处置/偏差三类型台账，
 * ALARM_HANDLE 双人核对 + 告警号回溯，登记完成发布 ward.cold-chain.alert-archived。
 *
 * <p>雪花代理主键（@TableId(ASSIGN_ID)）；content 为 JSONB 列（JsonbTypeHandler 承载原文透传）；
 * archive_no 关联完整性应用层保证（不建物理外键，V1101 迁移注释口径）。
 */
@Getter
@Setter
@TableName(value = "ward.cold_chain_record", autoResultMap = true)
public class ColdChainRecordEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 记录业务号（CCR{yyyyMMdd}{%05d}，WardSeqGate 签发，全局唯一） */
    private String recordNo;

    /** 所属档案号（关联 cold_chain_archive.archive_no，应用层保证存在） */
    private String archiveNo;

    /** 记录类型：INSPECTION/ALARM_HANDLE/DEVIATION */
    private ColdChainRecordType recordType;

    /** 关联告警号（ALARM_HANDLE 必填，其余类型为空），可空 */
    private String alarmRef;

    /** 双人核对第二人（ALARM_HANDLE 必填，其余类型为空），可空 */
    private String secondOperator;

    /** 记录内容（JSON 载体：巡检读数/处置措施/偏差描述），可空 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String content;

    /** 登记人（应用层写入操作者，系统动作为 system） */
    private String recordedBy;

    /** 登记时刻（巡检 overdue 判定窗口锚） */
    private OffsetDateTime recordedAt;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：种子/系统操作为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
