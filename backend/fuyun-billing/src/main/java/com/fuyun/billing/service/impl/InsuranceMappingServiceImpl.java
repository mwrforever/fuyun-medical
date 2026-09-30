package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.billing.dto.InsuranceMappingUpsertRequest;
import com.fuyun.billing.entity.InsuranceMapping;
import com.fuyun.billing.enums.MappingStatus;
import com.fuyun.billing.mapper.InsuranceMappingMapper;
import com.fuyun.billing.service.IInsuranceMappingService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 医保对照实现（billing.insurance_mapping，FU-M13-01 贯标载体）。
 *
 * <p>单 ACTIVE 行语义：部分唯一索引 uk_mapping_item_active 保证每项目至多一条 ACTIVE 行，
 * 应用层 upsert 按「存在 ACTIVE 行则原行改写、无则插新行」收口（目录切换保留原行 id 供追溯）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口，并发双插由部分唯一索引兜底。
 */
@Slf4j
public class InsuranceMappingServiceImpl extends ServiceImpl<InsuranceMappingMapper, InsuranceMapping>
        implements IInsuranceMappingService {

    /**
     * 取项目当前 ACTIVE 对照（贯标硬校验与取价快照消费入口）。
     *
     * @param chargeItemId 收费项目 id，非空
     * @return ACTIVE 对照行；未贯标或对照已失效返回 null
     */
    @Override
    @Transactional(readOnly = true)
    public InsuranceMapping effectiveMapping(long chargeItemId) {
        return lambdaQuery()
                .eq(InsuranceMapping::getChargeItemId, chargeItemId)
                .eq(InsuranceMapping::getStatus, MappingStatus.ACTIVE)
                .one();
    }

    /**
     * 批量取项目当前 ACTIVE 对照（批量快照取价消费，A.4.3-14 N+1 消除）。
     *
     * <p>与单查 {@link #effectiveMapping} 的语义分工：本方法只取数（ACTIVE 行按项目键返回，
     * 未贯标/已失效项目不出键），一次 IN 批查替代逐项目单查——多行单据快照批查的对照腿
     * 由 N 查收敛为 1 查；每项目至多一条 ACTIVE 行由部分唯一索引保证，无重复键面。
     *
     * @param chargeItemIds 收费项目 id 键集，非空集合（空集零 SQL 触达直接返回空 Map）
     * @return chargeItemId → ACTIVE 对照行（未贯标/已失效项目不出键，非 null）
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, InsuranceMapping> effectiveMappings(Collection<Long> chargeItemIds) {
        // 空键集守卫：MP in 谓词空集将生成非法 SQL，直接短路返回（无命中项目即零触达）
        if (chargeItemIds.isEmpty()) {
            return Map.of();
        }
        // 数据库读操作：ACTIVE 对照批查（charge_item_id IN + status 谓词，一次取回替代逐项目单查）
        return lambdaQuery()
                .in(InsuranceMapping::getChargeItemId, chargeItemIds)
                .eq(InsuranceMapping::getStatus, MappingStatus.ACTIVE)
                .list()
                .stream()
                .collect(Collectors.toMap(
                        InsuranceMapping::getChargeItemId,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
    }

    /**
     * 对照登记（upsert）：存在 ACTIVE 行则原行改写为最新对照，无则插入新 ACTIVE 行。
     *
     * @param req 对照登记请求，非空；来源：物价员对照国家目录录入
     * @return 落库对照行 id（改写为原行 id，新插为回填雪花 id）
     */
    @Override
    @Transactional
    public long upsert(InsuranceMappingUpsertRequest req) {
        // 数据库读操作：按项目查 ACTIVE 行（与部分唯一索引同口径，禁存多 ACTIVE）
        InsuranceMapping active = effectiveMapping(req.chargeItemId());
        if (active == null) {
            InsuranceMapping row = new InsuranceMapping();
            applyRequest(row, req);
            row.setStatus(MappingStatus.ACTIVE);
            save(row);
            log.info(
                    "医保对照新登记：chargeItemId={}，nhsaCode={}，mappingId={}",
                    req.chargeItemId(),
                    req.nhsaCode(),
                    row.getId());
            return row.getId();
        }
        // 数据库写操作：原 ACTIVE 行整体改写（保留 id 供审计追溯，禁删旧插新产生新 id）
        applyRequest(active, req);
        updateById(active);
        log.info("医保对照改写：chargeItemId={}，nhsaCode={}，mappingId={}", req.chargeItemId(), req.nhsaCode(), active.getId());
        return active.getId();
    }

    /** 请求字段 → 实体列映射（插入/改写两分支共用，禁散落复制漂移）。 */
    private void applyRequest(InsuranceMapping row, InsuranceMappingUpsertRequest req) {
        row.setChargeItemId(req.chargeItemId());
        row.setMapType(req.mapType());
        row.setNhsaCode(req.nhsaCode());
        row.setCatalogVersion(req.catalogVersion());
        row.setSelfPayRatio(req.selfPayRatio());
        row.setLimitPrice(req.limitPrice());
        row.setInsurancePayType(req.insurancePayType());
        row.setCheckReceipt(req.checkReceipt());
    }
}
