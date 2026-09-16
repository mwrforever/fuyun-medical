package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 合并记录实体（patient.merge_record，M02 §4/M-1）：(merged_patient_id) 部分唯一仅
 * PROCESSING/COMPLETED 生效——拆分后再合并可循环；pre_snapshot 为拆分回滚依据。
 *
 * <p>指针映射口径（M02 §3.3 拍板）：从档置 MERGED + merged_into 指针，历史数据零改写；
 * 本表快照（从档字段 + 标识挂接清单）承载可逆拆分（REVERSED 终态）。
 */
@Getter
@Setter
@TableName("patient.merge_record")
public class MergeRecord {

    /** 主键：雪花 ID（MP ASSIGN_ID 插入时生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 主档（合并后保留方） */
    private Long survivorPatientId;

    /** 从档（被合并方，置 MERGED + merged_into 指针） */
    private Long mergedPatientId;

    /** 合并原因（审计必填） */
    private String mergeReason;

    /** 合并前完整快照（JSON：从档字段 + 标识挂接清单；TEXT 列，拆分回滚依据，禁出接口层） */
    private String preSnapshot;

    /** 状态机：PROCESSING/COMPLETED/FAILED(可重试)/REVERSED(终态)（MergeStatus） */
    private String status;

    /** 经办人（双人角色：与审批人不得同人，应用层校验） */
    private String operator;

    /** 审批人（approve 动作落） */
    private String approvedBy;

    /** 合并完成时刻（COMPLETED 落） */
    private OffsetDateTime completedAt;

    /** 拆分时刻（REVERSED 落） */
    private OffsetDateTime reversedAt;

    /** 拆分原因 */
    private String reverseReason;

    /** 发起时刻：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时刻：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：默认 'system'，业务写路径由应用层注入操作人 */
    private String createdBy;

    /** 更新人：同上 */
    private String updatedBy;

    /** 逻辑删除标记（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
