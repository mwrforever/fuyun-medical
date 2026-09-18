package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.dto.PriceDraftRequest;
import com.fuyun.billing.entity.ChargeItemPrice;
import com.fuyun.billing.record.PriceSnapshot;
import java.util.List;

/**
 * 价格版本化服务（billing.charge_item_price，方案 3.4 版本化价格 + 计费快照）：
 * 「调价草稿 → 发布生效 → 闭旧区间 + 广播」三步闭环与计费快照取价唯一入口。
 */
public interface IChargePriceService extends IService<ChargeItemPrice> {

    /**
     * 取项目当前生效价格 + 医保对照冻结为计费快照（Task 11 计价引擎与 Task 15 结算取价唯一入口）。
     *
     * <p>「当前生效」按生效区间（半开 [effective_from, effective_to)）对取价时刻判定：发布未来起点
     * 版本后到点前仍取旧版本，到点自然切新版本（无调度器）。
     *
     * @param itemCode     项目编码（日志锚点），非空
     * @param chargeItemId 项目 id，非空
     * @return 价格快照，非空（nhsaCode/catalogVersion 等对照字段可空=未对照仅自费）
     * @throws com.fuyun.common.exception.BizException BILL-1008（409 无生效价格版本）
     */
    PriceSnapshot snapshot(String itemCode, long chargeItemId);

    /**
     * 调价草稿落库（版本号项目内自增，不触发生效——生效须显式 publish）。
     *
     * @param req 草稿请求，非空；来源：物价员录入（物价批文/协议定价）
     * @return 草稿行 id
     * @throws com.fuyun.common.exception.BizException BILL-1001（404 项目不存在）/ BILL-1003（409 停用）
     */
    long saveDraft(PriceDraftRequest req);

    /**
     * 发布调价（DRAFT→PUBLISHED，闭当前未闭版本区间并置新起点，广播工作站刷新）。
     *
     * <p>广播在发布时点发出、载荷携生效时刻 effectiveFrom；到点后的取价切换由
     * {@link #snapshot} 的区间判定承接（无调度器）。新起点早于当前版本起点即拒绝（调价不溯既往）。
     *
     * @param priceId 草稿行 id，非空
     * @throws com.fuyun.common.exception.BizException BILL-1005（404 缺行）/ BILL-1028（409 非 DRAFT 重复发布）
     *                                                / BILL-1004（409 新起点早于当前版本起点：区间倒挂）
     */
    void publish(long priceId);

    /**
     * 列项目价格版本链（管理面版本历史查询，version 倒序）。
     *
     * @param chargeItemId 项目 id，非空
     * @return 版本清单，可为空清单（项目尚无任何调价记录）
     */
    List<ChargeItemPrice> listVersions(long chargeItemId);
}
