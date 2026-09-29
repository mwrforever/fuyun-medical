package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.pharmacy.entity.DrugBatch;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import com.fuyun.pharmacy.service.IBatchSelectService;
import java.math.BigDecimal;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出库选批实现（FEFO）：候选全查 + 可用量过滤（现存量-锁定数 >= 请发数），FEFO 序取首个
 * 足量批次；锁定并发由 DrugBatchMapper.lockQuantity 条件更新兜底（读后过滤仅裁序，无竞态写）。
 */
public class BatchSelectServiceImpl extends ServiceImpl<DrugBatchMapper, DrugBatch> implements IBatchSelectService {

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import；ServiceImpl 继承保护字段 baseMapper
     * 子类内直赋，禁反射绕行）。
     *
     * @param drugBatchMapper 批次 mapper（与继承 baseMapper 同源），非空
     */
    public BatchSelectServiceImpl(DrugBatchMapper drugBatchMapper) {
        this.baseMapper = drugBatchMapper;
    }

    /**
     * 为发药 FEFO 选批（pick 配药链路选批与锁定防线的前半段，只读事务）：候选全查
     * （IN_STOCK 批次、expire_date 升序、同效期 production_date 升序）→ 内存过滤可用量
     * （现存量-锁定数 ≥ 请发数）→ 取首个足量批次。适用场景：门诊发药 pick 与发药中
     * 明细退场的批次定位。边界条件：单批足量约束（可用量不足整批返回 null，拆批分配
     * 随 P3 药房管理）；读后过滤仅裁序无竞态写，锁定并发由调用方 lockQuantity 条件更新兜底
     * （本方法不锁批次、不写任何行）。本方法自身无业务异常抛出面（无批次=null 语义出网，
     * 由调用方以 PH-1010 定性）。
     *
     * @param drugId     药品 id（drug.id），非空；来源：调剂明细行 drug_id（处方开立时快照）
     * @param storehouse 库房编码（如 P1 演示单一门诊药房 OUTP_PHARM），非空；来源：调剂单
     *                   storehouse 列
     * @param quantity   请发数量（基础单位，与批次 quantity/locked_qty 同口径），非空且 &gt;0；
     *                   来源：调剂明细 requested_qty
     * @return FEFO 首个足量在库批次行；无合格批次（全部缺量/过期/非在库）返回 null，调用方
     *         以 PH-1010 拒配药
     */
    @Override
    @Transactional(readOnly = true)
    public DrugBatch selectForDispense(long drugId, String storehouse, BigDecimal quantity) {
        // 数据库读操作：FEFO 序候选全查（近效期先出、同效期先产先出），可用量过滤后取首个足量批次
        return baseMapper
                .selectList(Wrappers.<DrugBatch>lambdaQuery()
                        .eq(DrugBatch::getDrugId, drugId)
                        .eq(DrugBatch::getStorehouse, storehouse)
                        .eq(DrugBatch::getStatus, "IN_STOCK")
                        .orderByAsc(DrugBatch::getExpireDate)
                        .orderByAsc(DrugBatch::getProductionDate))
                .stream()
                .filter(b -> b.getQuantity().subtract(b.getLockedQty()).compareTo(quantity) >= 0)
                .findFirst()
                .orElse(null);
    }
}
