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
