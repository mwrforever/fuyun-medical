package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.ChargeItemCreateRequest;
import com.fuyun.billing.dto.ComboComponentRequest;
import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.entity.ChargeItemComponent;
import com.fuyun.billing.enums.ItemPriceFlag;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.mapper.ChargeItemComponentMapper;
import com.fuyun.billing.mapper.ChargeItemMapper;
import com.fuyun.billing.service.IChargeItemService;
import com.fuyun.common.exception.BizException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收费项目管理（billing.charge_item，FU-M13-01 物价项目库权威源）。
 *
 * <p>药品/耗材业务属性引用 M06 主数据，本表仅存 item_code 级关联不复制字典（M13 §4）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class ChargeItemServiceImpl extends ServiceImpl<ChargeItemMapper, ChargeItem> implements IChargeItemService {

    private final ChargeItemComponentMapper componentMapper;

    /** 全参构造器（装配归 BillingWebConfig @Import）。 */
    public ChargeItemServiceImpl(ChargeItemComponentMapper componentMapper) {
        this.componentMapper = componentMapper;
    }

    /**
     * 新建收费项目：编码业务唯一前置守卫（uk 兜底），默认 SINGLE/ACTIVE。
     *
     * @param req 新建请求，非空；itemCode 来源：物价员录入
     * @return 新项目 id
     * @throws BizException BILL-1002（409 编码占用）
     */
    @Override
    @Transactional
    public long createChargeItem(ChargeItemCreateRequest req) {
        // 数据库读操作：编码等值查重（uk_charge_item_code 前置，禁裸插吞异常）
        ChargeItem existing =
                lambdaQuery().eq(ChargeItem::getItemCode, req.itemCode()).one();
        if (existing != null) {
            throw new BizException(BillingErrorCode.CHARGE_ITEM_CODE_EXISTS, HttpStatus.CONFLICT, "项目编码已存在");
        }
        ChargeItem item = new ChargeItem();
        item.setItemCode(req.itemCode());
        item.setItemName(req.itemName());
        item.setItemClass(req.itemClass());
        item.setUnit(req.unit());
        item.setExecDeptId(req.execDeptId());
        item.setFeeCategory(req.feeCategory());
        boolean combo = Boolean.TRUE.equals(req.comboFlag());
        item.setPriceFlag(combo ? ItemPriceFlag.COMBO_ONLY : ItemPriceFlag.SINGLE);
        item.setComboFlag(combo);
        item.setStatus(ItemStatus.ACTIVE);
        save(item);
        log.info("收费项目新建：itemCode={}，itemId={}", req.itemCode(), item.getId());
        return item.getId();
    }

    /**
     * 按编码取生效项目（计价引擎消费入口）。
     *
     * @param itemCode 项目编码，非空
     * @return ACTIVE 项目实体，非空
     * @throws BizException BILL-1001（404 缺项）/ BILL-1003（409 停用）
     */
    @Override
    @Transactional(readOnly = true)
    public ChargeItem requireActiveByCode(String itemCode) {
        ChargeItem item = lambdaQuery().eq(ChargeItem::getItemCode, itemCode).one();
        if (item == null) {
            throw new BizException(BillingErrorCode.CHARGE_ITEM_NOT_FOUND, HttpStatus.NOT_FOUND, "收费项目不存在：" + itemCode);
        }
        if (item.getStatus() != ItemStatus.ACTIVE) {
            throw new BizException(
                    BillingErrorCode.CHARGE_ITEM_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "收费项目已停用：" + itemCode);
        }
        return item;
    }

    /**
     * 组合构成维护（全量覆盖式落成员；仅 combo_flag=TRUE 项目可调）。
     *
     * @param comboItemId 组合项目 id
     * @param components  成员清单，非空
     * @throws BizException BILL-1003（非组合项目）
     */
    @Override
    @Transactional
    public void saveComboComponents(long comboItemId, List<ComboComponentRequest> components) {
        ChargeItem combo = getById(comboItemId);
        if (combo == null || !Boolean.TRUE.equals(combo.getComboFlag())) {
            throw new BizException(BillingErrorCode.CHARGE_ITEM_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "非组合项目不可维护构成");
        }
        // 数据库写操作：全量删旧成员（逻辑删）+ 批量插新（组合构成以最后一次维护为准）；
        // 非本 service 主表经 Wrappers 静态工厂（A.4.3-13，禁内联全限定，A.1-13）
        componentMapper.delete(
                Wrappers.<ChargeItemComponent>lambdaQuery().eq(ChargeItemComponent::getComboItemId, comboItemId));
        for (ComboComponentRequest c : components) {
            ChargeItemComponent row = new ChargeItemComponent();
            row.setComboItemId(comboItemId);
            row.setComponentItemId(c.componentItemId());
            row.setDefaultQuantity(c.defaultQuantity());
            componentMapper.insert(row);
        }
        log.info("组合构成维护：comboItemId={}，成员数={}", comboItemId, components.size());
    }

    /** 列组合成员（划价展开消费）。 */
    @Override
    @Transactional(readOnly = true)
    public List<ChargeItemComponent> listComponents(long comboItemId) {
        return componentMapper.selectList(
                Wrappers.<ChargeItemComponent>lambdaQuery().eq(ChargeItemComponent::getComboItemId, comboItemId));
    }
}
