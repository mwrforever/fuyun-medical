package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.dto.ChargeItemCreateRequest;
import com.fuyun.billing.dto.ComboComponentRequest;
import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.entity.ChargeItemComponent;
import java.util.List;

/**
 * 收费项目管理服务（billing.charge_item，FU-M13-01 物价项目库权威源）：
 * 项目建档 / 按码取生效项（计价引擎消费）/ 组合构成维护（划价展开消费）。
 */
public interface IChargeItemService extends IService<ChargeItem> {

    /**
     * 新建收费项目：编码业务唯一前置守卫（uk 兜底），默认 SINGLE/ACTIVE。
     *
     * @param req 新建请求，非空；itemCode 来源：物价员录入
     * @return 新项目 id
     * @throws com.fuyun.common.exception.BizException BILL-1002（409 编码占用）
     */
    long createChargeItem(ChargeItemCreateRequest req);

    /**
     * 按编码取生效项目（计价引擎消费入口，Task 11）。
     *
     * @param itemCode 项目编码，非空
     * @return ACTIVE 项目实体，非空
     * @throws com.fuyun.common.exception.BizException BILL-1001（404 缺项）/ BILL-1003（409 停用）
     */
    ChargeItem requireActiveByCode(String itemCode);

    /**
     * 组合构成维护（全量覆盖式落成员；仅 combo_flag=TRUE 项目可调）。
     *
     * @param comboItemId 组合项目 id
     * @param components  成员清单，非空
     * @throws com.fuyun.common.exception.BizException BILL-1003（非组合项目）
     */
    void saveComboComponents(long comboItemId, List<ComboComponentRequest> components);

    /**
     * 列组合成员（划价展开消费入口，Task 11）。
     *
     * @param comboItemId 组合项目 id
     * @return 成员清单，可为空清单
     */
    List<ChargeItemComponent> listComponents(long comboItemId);
}
