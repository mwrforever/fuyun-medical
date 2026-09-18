package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.ItemClass;
import com.fuyun.billing.enums.ItemPriceFlag;
import com.fuyun.billing.enums.ItemStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 收费项目实体（billing.charge_item，M13 §4；物价项目库权威源——药品/耗材仅 item_code 级关联
 * M06 主数据不复制字典。线程安全：可变实体仅 service 事务内使用，不出数据层）。
 */
@Getter
@Setter
@TableName("billing.charge_item")
public class ChargeItem {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 院内物价编码（业务唯一） */
    private String itemCode;

    /** 项目名称 */
    private String itemName;

    /** 类别（ItemClass 七值） */
    private ItemClass itemClass;

    /** 计价单位（M01 字典 code） */
    private String unit;

    /** 默认执行科室 id（可空） */
    private Long execDeptId;

    /** 收费标记（SINGLE 可单收/COMBO_ONLY 仅组合内） */
    private ItemPriceFlag priceFlag;

    /** 组合项目标记 */
    private Boolean comboFlag;

    /** 清单费用大类（M01 字典 code） */
    private String feeCategory;

    /** 状态 ACTIVE/INACTIVE */
    private ItemStatus status;

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
