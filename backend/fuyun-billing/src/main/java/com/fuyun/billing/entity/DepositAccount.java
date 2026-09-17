package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.DepositStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院预交金账户实体（billing.deposit_account，FU-M13-04）：一就诊一账户（uk_deposit_visit）；
 * 余额唯一写点为 DepositAccountMapper.mutateBalance 单语句原子 UPDATE RETURNING 回读，
 * 应用层禁散改 balance。门诊预交金按 2025-03 国家政策取消不设账户（Spec §12 澄清①）。
 * 线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.deposit_account")
public class DepositAccount {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 患者主索引 */
    private Long patientId;

    /** CF-3 住院就诊号（I 前缀，服务层校验） */
    private String visitId;

    /** 余额（分；变动经 mutateBalance 原子记账） */
    private Long balance;

    /** 欠费预警阈值（分，缴存时可覆盖默认参数） */
    private Long warningThreshold;

    /** 账户状态 NORMAL/ARREARS/SETTLED/CLOSED */
    private DepositStatus status;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护 */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列 */
    private String updatedBy;

    /** 逻辑删标记（@TableLogic 全局配置） */
    @TableLogic
    private Short deleted;
}
