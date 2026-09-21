package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 申请单明细行实体（outpatient.clinic_order_item，M03 医生站开单计费行）：item_code=M13 物价库
 * code（红线不自建价格，定价归 M13 计价引擎）；quantity 为 DECIMAL string 文本承载（D-18 数量
 * 出入参 string 同源，禁数值列精度漂移）。线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.clinic_order_item")
public class ClinicOrderItem {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 申请单主键（clinic_order.id） */
    private Long orderId;

    /** 项目编码（M13 物价库 code，计价与执行定位锚） */
    private String itemCode;

    /** 数量（DECIMAL string，如 "2"/"0.5"，红线禁数值列） */
    private String quantity;

    /** 用法摘要（频次/途径/注意事项快照），可空 */
    private String usageSummary;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列：操作人应用层注入 */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
