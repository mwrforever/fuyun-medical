package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.dto.DrugSaveRequest;
import com.fuyun.pharmacy.dto.InsuranceMappingRequest;
import com.fuyun.pharmacy.entity.Drug;
import com.fuyun.pharmacy.mapper.DrugMapper;
import com.fuyun.pharmacy.service.IDrugService;
import com.fuyun.pharmacy.vo.DrugVO;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 药品字典服务实现（FU-M06-01）：建档/变更走 uk 兜底+应用层先查的双防线；对照维护独立入口
 * 使「变更类型=MAPPING」可区分广播；检索默认启用面（停用药品不进选药场景）。
 * 装配归 PharmacyWebConfig @Import（禁组件扫描放宽，billing 九 impl 同款——Task 3 落配置类时
 * 统一注册）；changed 广播发布点由 Task 4 回挂（PharmacyEventPublisher 构造注入位在本类
 * 改造时一并加入，发布形态=事务内 publishEvent AFTER_COMMIT 出 fy.topic）。
 */
@Slf4j
public class DrugServiceImpl extends ServiceImpl<DrugMapper, Drug> implements IDrugService {

    /** 途径集分隔符（drug.route_codes 逗号分隔存储约定） */
    private static final String ROUTE_SEPARATOR = ",";

    /**
     * 构造器注入 mapper（@Import 装配期由容器解析 DrugMapper Bean；单测直建实例注入 mock）。
     *
     * @param drugMapper 药品字典 mapper，非空；来源：容器 Bean 或单测 mock
     */
    public DrugServiceImpl(DrugMapper drugMapper) {
        this.baseMapper = drugMapper;
    }

    @Override
    @Transactional
    public DrugVO create(DrugSaveRequest req) {
        // 数据库读操作：uk 前置查（唯一索引兜底并发，双防线与 billing charge_item 同型）
        Long exists = baseMapper.selectCount(Wrappers.<Drug>lambdaQuery().eq(Drug::getDrugCode, req.drugCode()));
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
        return DrugVO.from(row);
    }

    @Override
    @Transactional
    public DrugVO update(long id, DrugSaveRequest req) {
        Drug row = baseMapper.selectById(id);
        if (row == null) {
            throw new BizException(PharmacyErrorCode.DRUG_NOT_FOUND, HttpStatus.NOT_FOUND, "药品不存在：" + id);
        }
        applyRequest(row, req);
        // 数据库写操作：档案变更（对照三列与 status 不在覆盖面——applyRequest 不触碰）
        updateById(row);
        log.info("药品变更：drugCode={}，id={}，status={}", row.getDrugCode(), row.getId(), row.getStatus());
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
        Drug row = baseMapper.selectById(id);
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
        row.setSplitRatio(req.splitRatio() == null ? null : new BigDecimal(req.splitRatio()));
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
