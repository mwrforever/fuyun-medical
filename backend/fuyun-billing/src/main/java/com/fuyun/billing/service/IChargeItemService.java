package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.dto.ChargeItemCreateRequest;
import com.fuyun.billing.dto.ComboComponentRequest;
import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.entity.ChargeItemComponent;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 收费项目管理服务（billing.charge_item，FU-M13-01 物价项目库权威源）：
 * 项目建档 / 按码取生效项（单查供计费引擎消费，批查供划价预计价消费）/ 组合构成维护（划价展开消费）。
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
     * 按编码取生效项目（计价引擎消费入口）。
     *
     * @param itemCode 项目编码，非空
     * @return ACTIVE 项目实体，非空
     * @throws com.fuyun.common.exception.BizException BILL-1001（404 缺项）/ BILL-1003（409 停用）
     */
    ChargeItem requireActiveByCode(String itemCode);

    /**
     * 按编码集批量取收费项目（预计价链路批量取数，A.4.3-14 N+1 消除）：一次 IN 批查替代
     * 逐行 {@link #requireActiveByCode} 单查。
     *
     * <p>本方法不做生效守卫（区别于单查面）：INACTIVE 停用行同样返回——缺行（BILL-1001）与
     * 停用（BILL-1003）的区分判定由调用方批查后按原行序逐行补偿校验，错误语义与逐行单查
     * 完全一致；逻辑删行经 @TableLogic 自动滤除。
     *
     * @param itemCodes 项目编码键集，非空集合（空集零 SQL 触达直接返回空 Map）；来源：单据行 itemCode 去重
     * @return itemCode → 项目实体（含停用行；键集内无行编码不出键，非 null）
     */
    Map<String, ChargeItem> listByCodes(Collection<String> itemCodes);

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

    /**
     * 批量列组合成员（预计价链路组合展开批量取数，A.4.3-14 N+1 消除）：一次 IN 批查替代
     * 逐组合 {@link #listComponents} 单查，按组合分组返回。
     *
     * @param comboItemIds 组合项目 id 键集，非空集合（空集零 SQL 触达直接返回空 Map）
     * @return comboItemId → 成员清单（未维护构成的组合不出键，非 null）；组内按 id 升序
     *         （=维护插入序，与单查回放序一致且定序确定）
     */
    Map<Long, List<ChargeItemComponent>> listComponentsByComboItemIds(Collection<Long> comboItemIds);
}
