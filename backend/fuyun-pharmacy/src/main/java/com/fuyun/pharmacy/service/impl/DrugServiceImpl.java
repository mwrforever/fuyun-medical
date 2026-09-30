package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
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

    /**
     * 药品建档（FU-M06-01）：uk_drug_code 应用层前置查 + 唯一索引兜底并发（双防线，与
     * billing charge_item 同型）→ 请求面字段应用（对照三列不入建档面，insuredSettleable
     * 派生恒 false）→ 状态缺省 ENABLED（新建即启用）→ 落库 → info 留痕 → 事务内发布
     * drug.changed 广播（changeType=CREATE，AFTER_COMMIT 出 fy.topic，M05 病区药疗主数据
     * 缓存消费面）。适用场景：药品字典管理建档。
     *
     * @param req 建档请求（drugCode/genericName/routeCodes/splitRatio 等，splitRatio 为
     *            DECIMAL string 经 PH-1016 守卫解析），非空；来源：药品字典管理表单
     * @return 已建档药品 VO（含生成 id、status=ENABLED、insuredSettleable=false），非空
     * @throws BizException PH-1002（409 药品编码已存在——uk 前置查命中；并发穿透由
     *                      uk_drug_code 数据库层兜底）/ PH-1016（400 拆分比例非数字串）
     */
    @Override
    @Transactional
    public DrugVO create(DrugSaveRequest req) {
        // 数据库读操作：uk 前置查（唯一索引兜底并发，双防线与 billing charge_item 同型）；
        //   主表查询走 ServiceImpl 内置 lambdaQuery 链式（宪法 A.4.3-13），条件谓词与链式化前逐字等价
        Long exists = lambdaQuery().eq(Drug::getDrugCode, req.drugCode()).count();
        if (exists != null && exists > 0) {
            throw new BizException(
                    PharmacyErrorCode.DRUG_CODE_EXISTS, HttpStatus.CONFLICT, "药品编码已存在：" + req.drugCode());
        }
        Drug row = new Drug();
        // 请求面应用（splitRatio 单次解析随行复用——W-22⑦；建档面与变更面共用同款应用器）
        applyRequest(row, req, req.splitRatio() == null ? null : parseSplitRatio(req.splitRatio()));
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

    /**
     * 药品档案变更（FU-M06-01）：存在性校验（404）→ 请求面字段覆写（对照三列与 status
     * 不在覆盖面——applyRequest 不触碰，禁变更通道改对照/停启用）→ 指定列补丁落库
     * （EX-24｜BE-A2-05 并发防覆写：仅携 id+请求面列，未携列不进 SET）→ info 留痕 →
     * 事务内发布 drug.changed 广播（changeType=UPDATE，AFTER_COMMIT 出线，M05 主数据
     * 缓存失效消费面）。适用场景：药品字典管理档案维护（规格/途径集/警示级别等）。
     *
     * @param id  药品行 id，非空；来源：字典管理列表
     * @param req 变更请求（与 create 同面，对照三列与 status 入参被忽略），非空；来源：
     *            药品字典管理表单
     * @return 变更后药品 VO（status 维持库内原值），非空
     * @throws BizException PH-1001（404 药品不存在）/ PH-1016（400 拆分比例非数字串）
     */
    @Override
    @Transactional
    public DrugVO update(long id, DrugSaveRequest req) {
        Drug row = drugMapper.selectById(id);
        if (row == null) {
            throw new BizException(PharmacyErrorCode.DRUG_NOT_FOUND, HttpStatus.NOT_FOUND, "药品不存在：" + id);
        }
        // 请求面单次解析（W-22⑦ 随行复用禁二次 parse）：读行同步与补丁回写共用同一解析结果
        BigDecimal splitRatio = req.splitRatio() == null ? null : parseSplitRatio(req.splitRatio());
        // 内存读行同步请求面：出参 VO/日志/广播载荷直取（对照三列与 status 保持读侧原值）
        applyRequest(row, req, splitRatio);
        // 数据库写操作：档案变更走指定列补丁回写（EX-24｜BE-A2-05 并发防覆写）——仅携 id+请求面列
        //   的补丁实体落库，NOT_NULL 更新策略下未携列不进 SET 子句：读改写窗口内 mapInsurance
        //   并发提交的对照三列、停启用通道的 status 不被读点整行快照覆写吞掉（对照三列与 status
        //   不在覆盖面——applyRequest 不触碰；TriageServiceImpl BUG-07 补丁回写同款形态）
        Drug patch = new Drug();
        patch.setId(id);
        applyRequest(patch, req, splitRatio);
        updateById(patch);
        log.info("药品变更：drugCode={}，id={}，status={}", row.getDrugCode(), row.getId(), row.getStatus());
        // 事务内发应用事件（A.4.2-7 禁事务内直发 MQ）：变更留痕广播 changeType=UPDATE
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_DRUG_CHANGED,
                new DrugChangedPayload(String.valueOf(row.getId()), row.getDrugCode(), "UPDATE")));
        return DrugVO.from(row);
    }

    /**
     * 药品详情（只读事务，字典管理/开方选药回显）：按 id 定位并转 VO，缺行显式 404。
     * 注意本查询不滤停用面（停用药品详情仍可回显，选药检索面由 search 默认启用面承载）。
     *
     * @param id 药品行 id，非空；来源：字典管理列表/开方回显
     * @return 药品 VO（含对照三列与 status 全量面），非空
     * @throws BizException PH-1001（404 药品不存在——建议处理：核对 id 或引导建档）
     */
    @Override
    @Transactional(readOnly = true)
    public DrugVO get(long id) {
        Drug row = baseMapper.selectById(id);
        if (row == null) {
            throw new BizException(PharmacyErrorCode.DRUG_NOT_FOUND, HttpStatus.NOT_FOUND, "药品不存在：" + id);
        }
        return DrugVO.from(row);
    }

    /**
     * 医保对照维护（FU-M06-01 独立入口）：存在性校验（404）→ 对照三列（nhsa_code/
     * nhsa_catalog_version/nhsa_pay_type）指定列补丁落行（EX-24｜BE-A2-05 并发防覆写：
     * 仅携 id+对照三列，档案面/status 未携列不进 SET；目录版本随对照维护动态更新口径，Spec :154；
     * insuredSettleable 由计费引擎按对照派生，本入口不触）→ info 留痕 → 事务内发布
     * drug.changed 广播（changeType=MAPPING，独立类型使消费方可区分对照变更与档案变更）。
     * 适用场景：医保对照管理台。
     *
     * @param id  药品行 id，非空；来源：对照管理列表
     * @param req 对照请求（nhsaCode/catalogVersion/payType），非空；来源：医保对照管理表单
     * @throws BizException PH-1001（404 药品不存在）
     */
    @Override
    @Transactional
    public void mapInsurance(long id, InsuranceMappingRequest req) {
        Drug row = drugMapper.selectById(id);
        if (row == null) {
            throw new BizException(PharmacyErrorCode.DRUG_NOT_FOUND, HttpStatus.NOT_FOUND, "药品不存在：" + id);
        }
        // 内存读行同步对照三列（日志与内存态读侧一致；drugCode 直取读点原值）
        row.setNhsaCode(req.nhsaCode());
        row.setNhsaCatalogVersion(req.catalogVersion());
        row.setNhsaPayType(req.payType());
        // 数据库写操作：对照三列走指定列补丁回写（EX-24｜BE-A2-05 并发防覆写，目录版本随对照
        //   维护动态更新口径 Spec :154）——仅携 id+对照三列的补丁实体落库，NOT_NULL 更新策略下
        //   档案面/status 未携列不进 SET 子句：读改写窗口内 update() 并发提交的档案变更不被
        //   读点整行快照覆写吞掉（TriageServiceImpl BUG-07 补丁回写同款形态）
        Drug patch = new Drug();
        patch.setId(id);
        patch.setNhsaCode(req.nhsaCode());
        patch.setNhsaCatalogVersion(req.catalogVersion());
        patch.setNhsaPayType(req.payType());
        updateById(patch);
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

    /**
     * 药品检索（只读事务，选药场景默认启用面——停用药品不进结果）：谓词组装收敛
     * buildSearchWrapper（keyword 非空时通用名/商品名/拼音/医保码四列 OR 前缀匹配；
     * id 升序 A.4.3-17 唯一顺序约束）→ MP 分页（0 基请求转 1 基 current）→ VO 投影。
     * 适用场景：M03 开方选药 / 字典管理检索。
     *
     * @param keyword         关键词（四列 OR 前缀匹配），可空/空白（空=不加关键词谓词）；
     *                        来源：选药搜索框
     * @param essential       基药过滤（true 仅基药/false 仅非基药），可空（空=不过滤）；
     *                        来源：选药筛选器
     * @param antibioClass    抗菌药分级过滤 code（UNRESTRICTED/RESTRICTED/SPECIAL），可空/
     *                        空白（空=不过滤）；来源：选药筛选器
     * @param insuranceMapped 医保对照过滤（true 仅已对照 nhsa_code 非空），可空（null/false
     *                        不过滤）；来源：选药筛选器
     * @param page            页码（0 基），&ge;0；来源：分页组件
     * @param size            页大小，&gt;0；来源：分页组件
     * @return 分页结果（启用面药品 VO，id 升序），非空；无命中为空 content 页
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<DrugVO> search(
            String keyword, Boolean essential, String antibioClass, Boolean insuranceMapped, int page, int size) {
        // 数据库读操作：谓词组装收敛 buildSearchWrapper（0 基请求转 MP 1 基 current，与 billing page 同型）；
        //   主表分页走链式 .page 终态（宪法 A.4.3-13，与 PrescriptionServiceImpl.list 同款成交形态）
        Page<Drug> result = buildSearchWrapper(keyword, essential, antibioClass, insuranceMapped)
                .page(new Page<>(page + 1, size));
        List<DrugVO> content = result.getRecords().stream().map(DrugVO::from).toList();
        return PageResult.of(content, page, size, result.getTotal());
    }

    /**
     * 检索谓词组装（包级可见供单测对链式终态做 SQL 契约断言；keyword 非空时
     * 通用名/商品名/拼音/医保码四列 OR 前缀匹配，insuranceMapped=true 附加 IS NOT NULL 谓词；
     * 默认启用面——停用药品不进选药场景）。主表谓词走 ServiceImpl 内置 lambdaQuery 链式
     * （宪法 A.4.3-13）：EX-12 遗留的手构形态已随断言现代化专项收口（D-21 回归红线出口，
     * 单点单次：单测断言由 LambdaQueryWrapper 强转形态升级为 SQL 契约形态——getSqlSegment
     * 逐子句 + 参数绑定值全量精确匹配），可选谓词由 boolean 条件重载承载（EX-12 其余八处
     * 成交同款）。
     *
     * @param keyword         关键词，可空
     * @param essential       基药过滤，可空
     * @param antibioClass    分级过滤 code，可空
     * @param insuranceMapped 对照过滤，可空
     * @return 检索链式 wrapper（id 升序，A.4.3-17 唯一顺序约束），调用方以 .page 终态消费
     */
    LambdaQueryChainWrapper<Drug> buildSearchWrapper(
            String keyword, Boolean essential, String antibioClass, Boolean insuranceMapped) {
        // 数据库读操作：主表谓词组装走 ServiceImpl 内置 lambdaQuery 链式（宪法 A.4.3-13），
        //   条件/序键与手构形态逐字等价（可选谓词由 boolean 条件重载承载，假值即不进 WHERE）
        return lambdaQuery()
                .eq(Drug::getStatus, "ENABLED")
                .and(keyword != null && !keyword.isBlank(), w -> w.likeRight(Drug::getGenericName, keyword)
                        .or()
                        .likeRight(Drug::getTradeName, keyword)
                        .or()
                        .likeRight(Drug::getPinyinCode, keyword)
                        .or()
                        .likeRight(Drug::getNhsaCode, keyword))
                .eq(essential != null, Drug::getEssentialFlag, essential)
                .eq(antibioClass != null && !antibioClass.isBlank(), Drug::getAntibioClass, antibioClass)
                .isNotNull(Boolean.TRUE.equals(insuranceMapped), Drug::getNhsaCode)
                .orderByAsc(Drug::getId);
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

    /**
     * 请求面应用到实体（对照三列与 status 不在覆盖面）。建档面与变更面（读行同步+补丁实体）共用，
     * splitRatio 由调用方单次解析传入（W-22⑦ 随行复用禁二次 parse）。
     *
     * @param row        目标实体（建档新行/变更读行/指定列补丁实体），非空
     * @param req        请求面，非空
     * @param splitRatio 已解析拆分比例（请求缺省为 null），可空
     */
    private void applyRequest(Drug row, DrugSaveRequest req, BigDecimal splitRatio) {
        row.setDrugCode(req.drugCode());
        row.setGenericName(req.genericName());
        row.setTradeName(req.tradeName());
        row.setPinyinCode(req.pinyinCode());
        row.setDosageForm(req.dosageForm());
        row.setSpecification(req.specification());
        row.setManufacturer(req.manufacturer());
        row.setRouteCodes(req.routeCodes() == null ? null : String.join(ROUTE_SEPARATOR, req.routeCodes()));
        row.setUnit(req.unit());
        row.setSplitRatio(splitRatio);
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
