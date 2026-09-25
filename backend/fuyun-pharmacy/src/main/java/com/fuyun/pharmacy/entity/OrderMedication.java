package com.fuyun.pharmacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院医嘱用药快照实体（pharmacy.order_medication，V1000）：消费 inpatient.order.created drug
 * 子键事件落库的明细快照——事件载荷为唯一权威（禁跨模块读 M04 表）；uk_medication_order_no
 * 保证同一医嘱仅一条快照（重复投递/重提重发幂等兜底）。快照仅药品/剂量/途径/数量，禁患者
 * 姓名/诊断文本（脱敏红线，载荷契约亦不携带）。
 */
@Getter
@Setter
@TableName("pharmacy.order_medication")
public class OrderMedication {

    /** 雪花主键（MP ASSIGN_ID 插入期回填） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 住院医嘱号（M04 medical_order.order_no；审方回执 target 定位键） */
    private String m04OrderNo;

    /** 住院就诊号（I 型 14 位，M02 结构规范） */
    private String visitId;

    /** 患者主索引（M02） */
    private Long patientId;

    /** 频次编码（长期医嘱非空、临时医嘱可空；快照自事件载荷 freqCode） */
    private String freqCode;

    /** 医嘱项明细快照 JSON 数组文本（itemSeq/itemCode/itemName/dosage/unit/route/quantity/itemType） */
    private String items;

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
