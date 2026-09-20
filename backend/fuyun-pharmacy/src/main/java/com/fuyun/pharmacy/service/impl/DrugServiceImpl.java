package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.api.DrugChangedPayload;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.dto.DrugSaveRequest;
import com.fuyun.pharmacy.dto.InsuranceMappingRequest;
import com.fuyun.pharmacy.entity.Drug;
import com.fuyun.pharmacy.internal.PharmacyDomainEvent;
import com.fuyun.pharmacy.mapper.DrugMapper;
import com.fuyun.pharmacy.service.IDrugService;
import com.fuyun.pharmacy.vo.DrugVO;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 药品字典服务实现（FU-M06-01）：建档/变更走 uk 兜底+应用层先查的双防线；对照维护独立入口
 * 使「变更类型=MAPPING」可区分广播；检索默认启用面（停用药品不进选药场景）。
 * 装配归 PharmacyWebConfig @Import（禁组件扫描放宽，billing 九 impl 同款——Task 3 落配置类时
 * 统一注册）；changed 广播发布形态=事务内 publishEvent AFTER_COMMIT 出 fy.topic
 * （PharmacyEventPublisher 承载出线时机）。
 */
@Slf4j
public class DrugServiceImpl extends ServiceImpl<DrugMapper, Drug> implements IDrugService {

    /** 途径集分隔符（drug.route_codes 逗号分隔存储约定） */
    private static final String ROUTE_SEPARATOR = ",";

    private final DrugMapper drugMapper;

    /** 应用事件发布器（drug.changed 广播 AFTER_COMMIT 出 fy.topic），非空 */
    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import）。
     *
     * @param drugMapper 药品 mapper（ServiceImpl 继承 baseMapper 同源），非空
     * @param events     应用事件发布器，非空
     */
    public DrugServiceImpl(DrugMapper drugMapper, ApplicationEventPublisher events) {
        this.drugMapper = drugMapper;
        this.events = events;
    }

    @Override
    @Transactional
    public DrugVO create(DrugSaveRequest req) {
        // 数据库读操作：uk 前置查（唯一索引兜底并发，双防线与 billing charge_item 同型）
        Long exists = drugMapper.selectCount(Wrappers.<Drug>lambdaQuery().eq(Drug::getDrugCode, req.drugCode()));
        if (exists != null && exists > 0) {
            throw new BizException(
                    PharmacyErrorCode.DRUG_CODE_EXISTS, HttpStatus.CONFLICT, "药品编码已存在：" + req.drugCode());
        }
        Drug row = new Drug();
        applyRequest(row, req);
        // 状态缺省：新建即启用（对照字段不入建档面，insuredSettleable 派生恒 false）
        row.setStatus("ENABLED");
        // 数据库写操作：建档落库
        save(row);
        log.info(
                "药品建档：drugCode={}，genericName={}，antibioClass={}，hazardLevel={}，insuredSettleable=false",
                row.getDrugCode(),
                row.getGenericName(),
                row.getAntibioClass(),
                row.getHazardLevel());
        // 事务内发应用事件（A.4.2-7 禁事务内直发 MQ）：变更留痕广播 changeType=CREATE
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_DRUG_CHANGED,
                new DrugChangedPayload(String.valueOf(row.getId()), row.getDrugCode(), "CREATE")));
        return DrugVO.from(row);
    }

    @Override
    @Transactional
    public DrugVO update(long id, DrugSaveRequest req) {
        Drug row = drugMapper.selectById(id);
        if (row == null) {
            throw new BizException(PharmacyErrorCode.DRUG_NOT_FOUND, HttpStatus.NOT_FOUND, "药品不存在：" + id);
        }
        applyRequest(row, req);
        // 数据库写操作：档案变更（对照三列与 status 不在覆盖面——applyRequest 不触碰）
        updateById(row);
        log.info("药品变更：drugCode={}，id={}，status={}", row.getDrugCode(), row.getId(), row.getStatus());
        // 事务内发应用事件（A.4.2-7 禁事务内直发 MQ）：变更留痕广播 changeType=UPDATE
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_DRUG_CHANGED,
                new DrugChangedPayload(String.valueOf(row.getId()), row.getDrugCode(), "UPDATE")));
        return DrugVO.from(row);
    }

    @Override
    @Transactional(readOnly = true)
    public DrugVO get(long id) {
        Drug row = baseMapper.selectById(id);
        if (row == null) {
            throw new BizException(PharmacyErrorCode.DRUG_NOT_FOUND, HttpStatus.NOT_FOUND, "药品不存在：" + id);
        }
        return DrugVO.from(row);
    }

    @Override
    @Transactional
    public void mapInsurance(long id, InsuranceMappingRequest req) {
        Drug row = drugMapper.selectById(id);
        if (row == null) {
            throw new BizException(PharmacyErrorCode.DRUG_NOT_FOUND, HttpStatus.NOT_FOUND, "药品不存在：" + id);
        }
        // 数据库写操作：对照三列落行（目录版本随对照维护，动态更新口径 Spec :154）
        row.setNhsaCode(req.nhsaCode());
        row.setNhsaCatalogVersion(req.catalogVersion());
        row.setNhsaPayType(req.payType());
        updateById(row);
        log.info(
                "药品医保对照维护：drugCode={}，nhsaCode={}，catalog={}，payType={}",
                row.getDrugCode(),
                req.nhsaCode(),
                req.catalogVersion(),
                req.payType());
        // 事务内发应用事件（A.4.2-7 禁事务内直发 MQ）：变更留痕广播 changeType=MAPPING
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_DRUG_CHANGED,
                new DrugChangedPayload(String.valueOf(row.getId()), row.getDrugCode(), "MAPPING")));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DrugVO> search(
            String keyword, Boolean essential, String antibioClass, Boolean insuranceMapped, int page, int size) {
        // 数据库读操作：谓词组装收敛 buildSearchWrapper（0 基请求转 MP 1 基 current，与 billing page 同型）
        Page<Drug> result = baseMapper.selectPage(
                new Page<>(page + 1, size), buildSearchWrapper(keyword, essential, antibioClass, insuranceMapped));
        List<DrugVO> content = result.getRecords().stream().map(DrugVO::from).toList();
        return PageResult.of(content, page, size, result.getTotal());
    }

    /**
     * 检索谓词组装（包级可见供单测对 getSqlSegment 做 contains 断言；keyword 非空时
     * 通用名/商品名/拼音/医保码四列 OR 前缀匹配，insuranceMapped=true 附加 IS NOT NULL 谓词；
     * 默认启用面——停用药品不进选药场景）。
     *
     * @param keyword         关键词，可空
     * @param essential       基药过滤，可空
     * @param antibioClass    分级过滤 code，可空
     * @param insuranceMapped 对照过滤，可空
     * @return 检索 wrapper（id 升序，A.4.3-17 唯一顺序约束）
     */
    Wrapper<Drug> buildSearchWrapper(String keyword, Boolean essential, String antibioClass, Boolean insuranceMapped) {
        var wrapper = Wrappers.<Drug>lambdaQuery().eq(Drug::getStatus, "ENABLED");
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.likeRight(Drug::getGenericName, keyword)
                    .or()
                    .likeRight(Drug::getTradeName, keyword)
                    .or()
                    .likeRight(Drug::getPinyinCode, keyword)
                    .or()
                    .likeRight(Drug::getNhsaCode, keyword));
        }
        wrapper.eq(essential != null, Drug::getEssentialFlag, essential)
                .eq(antibioClass != null && !antibioClass.isBlank(), Drug::getAntibioClass, antibioClass);
        if (Boolean.TRUE.equals(insuranceMapped)) {
            wrapper.isNotNull(Drug::getNhsaCode);
        }
        return wrapper.orderByAsc(Drug::getId);
    }

    /**
     * 药品拆分比例解析守卫（PH-1016，W-22⑦）：splitRatio 为 DECIMAL string 承载，非数字串显式
     * 拒 400（禁 NumberFormatException 直穿 500 出契约外形态）；可空字段缺省不入本守卫。
     *
     * @param splitRatio 拆分比例 DECIMAL string，非空（可空性由调用方三元承载）
     * @return 已解析比例值
     * @throws BizException PH-1016（400）：非数字串
     */
    private static BigDecimal parseSplitRatio(String splitRatio) {
        try {
            return new BigDecimal(splitRatio);
        } catch (NumberFormatException e) {
            throw new BizException(
                    PharmacyErrorCode.NUMERIC_FIELD_MALFORMED, HttpStatus.BAD_REQUEST, "药品拆分比例须为数字串：" + splitRatio);
        }
    }

    /** 请求面应用到实体（对照三列与 status 不在覆盖面） */
    private void applyRequest(Drug row, DrugSaveRequest req) {
        row.setDrugCode(req.drugCode());
        row.setGenericName(req.genericName());
        row.setTradeName(req.tradeName());
        row.setPinyinCode(req.pinyinCode());
        row.setDosageForm(req.dosageForm());
        row.setSpecification(req.specification());
        row.setManufacturer(req.manufacturer());
        row.setRouteCodes(req.routeCodes() == null ? null : String.join(ROUTE_SEPARATOR, req.routeCodes()));
        row.setUnit(req.unit());
        row.setSplitRatio(req.splitRatio() == null ? null : parseSplitRatio(req.splitRatio()));
        row.setEssentialFlag(req.essentialFlag());
        row.setAntibioClass(req.antibioClass());
        row.setHazardLevel(req.hazardLevel());
        row.setSkinTestFlag(req.skinTestFlag());
        row.setNarcoticClass(req.narcoticClass());
        row.setItemCode(req.itemCode());
        row.setTraceCodeType(req.traceCodeType());
        row.setIndication(req.indication());
        row.setMaxDose(req.maxDose());
        row.setContraindication(req.contraindication());
        row.setStorageCondition(req.storageCondition());
    }
}
