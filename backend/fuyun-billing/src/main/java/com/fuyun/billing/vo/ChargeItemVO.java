package com.fuyun.billing.vo;

import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.enums.ItemClass;
import com.fuyun.billing.enums.ItemPriceFlag;
import com.fuyun.billing.enums.ItemStatus;
import lombok.Getter;
import lombok.Setter;

/**
 * 收费项目出参（FU-M13-01 管理面）：物价项目库查询/新建回显载体。
 * id 经 Long 包装出网（金额红线出参口径）；金额字段不落本 VO（单价走价格版本 VO，Task 10）。
 */
@Getter
@Setter
public class ChargeItemVO {

    /** 项目 id（雪花） */
    private Long id;

    /** 院内物价编码（业务唯一） */
    private String itemCode;

    /** 项目名称 */
    private String itemName;

    /** 类别（ItemClass 七值，JSON 输出 code） */
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

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出）。
     *
     * @param item 项目实体，非空；来源：service 事务内查询结果
     * @return 出参 VO，非空
     */
    public static ChargeItemVO from(ChargeItem item) {
        ChargeItemVO vo = new ChargeItemVO();
        vo.setId(item.getId());
        vo.setItemCode(item.getItemCode());
        vo.setItemName(item.getItemName());
        vo.setItemClass(item.getItemClass());
        vo.setUnit(item.getUnit());
        vo.setExecDeptId(item.getExecDeptId());
        vo.setPriceFlag(item.getPriceFlag());
        vo.setComboFlag(item.getComboFlag());
        vo.setFeeCategory(item.getFeeCategory());
        vo.setStatus(item.getStatus());
        return vo;
    }
}
