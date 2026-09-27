package com.fuyun.ward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.ward.enums.CallSource;
import com.fuyun.ward.enums.CallStatus;
import com.fuyun.ward.enums.CallType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 呼叫行实体（ward.ward_call，V1100 迁移）：呼叫对讲状态机载体（六态迁移 + 动作式升级 + 合并取消）。
 *
 * <p>雪花代理主键（@TableId(ASSIGN_ID)）；对外标识为 call_no 业务号（WardSeqGate.nextCallNo，
 * CALL{yyyyMMdd}{%05d}）；updated_at 由数据库触发器统一维护，应用层不写时间戳列。
 */
@Getter
@Setter
@TableName("ward.ward_call")
public class WardCallEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 呼叫业务号（CALL{yyyyMMdd}{%05d}，WardSeqGate 签发，全局唯一） */
    private String callNo;

    /** 病区 ID（列表过滤与推送路由锚） */
    private Long wardId;

    /** 床位 ID（设备源/输液档可空；同床位合并取消锚），可空 */
    private Long bedId;

    /** 患者主索引（绑定快照冗余），可空 */
    private Long patientId;

    /** 设备号（IOT 源/输液档落值，手工创建为空），可空 */
    private String deviceId;

    /** 呼叫类型：NORMAL/EMERGENCY/INFUSION/SERVICE */
    private CallType callType;

    /** 呼叫来源：BEDSIDE/BRROOM/PATIENT_PAD/NURSE_PAD/IOT */
    private CallSource source;

    /** 呼叫状态：CREATED/ANSWERED/IN_PROGRESS/COMPLETED/TRANSFERRED/CANCELLED */
    private CallStatus status;

    /** 已升级次数（升级动作式防重发锚：CAS 限定 0 值，仅首个判定方递增，状态不变仍可应答） */
    private Integer escalationCount;

    /** 处理人（IN_PROGRESS 写入），可空 */
    private String processedBy;

    /** 处理结果摘要（COMPLETED 必填——应用层校验），可空 */
    private String resultSummary;

    /** 来源引用（INFUSION 档=告警号，其余为空），可空 */
    private String sourceRef;

    /** 应答时刻（→ANSWERED 写入），可空 */
    private OffsetDateTime answeredAt;

    /** 完成时刻（→COMPLETED 写入），可空 */
    private OffsetDateTime completedAt;

    /** 创建时间：数据库 DEFAULT now() 维护（升级时限计算锚） */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：系统落行为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
