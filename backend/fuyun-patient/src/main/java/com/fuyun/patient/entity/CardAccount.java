package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 一卡通账户（M02 §4）：余额台账，资金动作在 M13；balance BIGINT 分值制。
 *
 * <p>一人一账户（uk_card_account_patient 部分唯一索引兜底，逻辑删行不占用唯一性）；
 * 余额唯一写点为 {@code CardAccountMapper#recordBalance} 单语句原子 UPDATE（审查 I5 串行化台账），
 * 应用层禁散改 balance；updated_at 由 V104 触发器维护。
 */
@Getter
@Setter
@TableName("patient.card_account")
public class CardAccount {

    /** 账户 id（雪花，MP ASSIGN_ID 插入时生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 患者主索引（1:1，一卡通为可选启用项） */
    private Long patientId;

    /** 余额（分；金额恒 BIGINT 分值制，A.4.2-8 禁浮点） */
    private Long balance;

    /** 状态机：ACTIVE/FROZEN(挂失联动)/CLOSED(销户余额必须为零)（CardAccountStatus） */
    private String status;

    /** 开户时刻：数据库 DEFAULT now() 维护 */
    private OffsetDateTime openedAt;

    /** 销户时刻（CLOSED 后非空） */
    private OffsetDateTime closedAt;

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
