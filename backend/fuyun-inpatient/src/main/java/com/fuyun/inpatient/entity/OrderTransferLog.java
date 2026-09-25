package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院医嘱转抄记录实体（inpatient.order_transfer_log，V906）——流水型只增表（对齐 V905
 * order_audit/order_status_log 先例：保留审计列与 deleted 列形态，业务面仅 INSERT 不作
 * UPDATE/DELETE）：每次 AUDITED→TRANSFERRED 迁移落一行双人核对留痕（转抄护士/时点/结论/
 * 第二核对人——04 Spec §4 表注，高危/输血强制第二核对人由应用层 IP-1016 校验面守卫）。
 */
@Getter
@Setter
@TableName("inpatient.order_transfer_log")
public class OrderTransferLog {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属医嘱主键（medical_order 1:N order_transfer_log） */
    private Long orderId;

    /** 转抄护士（员工 ID string；双人核对的执行转抄方签名） */
    private String transferNurse;

    /** 转抄时点（服务器时间；transferred 事件 firstTransferredAt 载荷同源） */
    private OffsetDateTime transferredAt;

    /** 核对结论（CheckConclusion 两值：PASSED 核对通过/REJECTED 核对不符） */
    private String conclusion;

    /** 第二核对人（员工 ID string；高危/输血类强制非空，否则 null） */
    private String secondCheckerId;

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
