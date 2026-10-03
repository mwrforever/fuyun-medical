package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 扫码核对流水实体（nursing.execution_check_log，V1106）：只增表——腕带/袋签/设备三向扫码
 * 核对与破码放行的全量留痕（PASS/FAIL 双落行，FAIL 行携 fail_type 判定），单条执行单多轮
 * 核对的历史底座。条码原文禁全文落库（等保敏感面），仅落脱敏摘要（前 4 后 2 明文+总长度）。
 */
@Getter
@Setter
@TableName("nursing.execution_check_log")
public class ExecutionCheckLog {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 执行单号（order_execution 引用；一次执行多轮核对留痕） */
    private String executionNo;

    /** 核对方式（CheckType code：WRISTBAND 腕带扫描 / BAG_LABEL 袋签扫描 / DEVICE 设备绑定核对 / OVERRIDE 破码放行） */
    private String checkType;

    /** 核对结论（PASS 通过 / FAIL 失败） */
    private String checkResult;

    /** 失败类型（check_result=FAIL 时必填：WRISTBAND_MISMATCH/BAG_MISMATCH/DEVICE_MISMATCH/WRONG_PATIENT/OTHER） */
    private String failType;

    /** 扫码摘要（脱敏口径：前 4 后 2 明文+总长度，禁全文——条码原文属可回放敏感面；破码放行行=放行理由摘要） */
    private String codeDigest;

    /** 核对操作护士员工 ID（破码放行行=被授权放行者） */
    private Long operatorId;

    /** 核对发生时点（服务器时间） */
    private OffsetDateTime occurredAt;

    /** 创建时刻（DB now() 默认） */
    private OffsetDateTime createdAt;

    /** 更新时刻（DB now() 默认 + 触发器维护） */
    private OffsetDateTime updatedAt;

    /** 创建者 */
    private String createdBy;

    /** 更新者 */
    private String updatedBy;

    /** 逻辑删标记 */
    @TableLogic
    private Integer deleted;
}
