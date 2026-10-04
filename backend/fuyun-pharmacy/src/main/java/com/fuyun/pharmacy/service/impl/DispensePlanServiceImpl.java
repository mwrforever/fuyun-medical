package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.api.PatientNameQuery;
import com.fuyun.pharmacy.api.DispenseCompletedPayload;
import com.fuyun.pharmacy.api.DispenseReturnedPayload;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.cache.PharmacySeqGate;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.dto.DispensePlanGenerateRequest;
import com.fuyun.pharmacy.dto.DispenseReturnRequest;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import com.fuyun.pharmacy.entity.DispensePlan;
import com.fuyun.pharmacy.entity.Drug;
import com.fuyun.pharmacy.entity.DrugBatch;
import com.fuyun.pharmacy.entity.OrderMedication;
import com.fuyun.pharmacy.entity.ReviewTask;
import com.fuyun.pharmacy.entity.StockLedger;
import com.fuyun.pharmacy.internal.PharmacyDomainEvent;
import com.fuyun.pharmacy.mapper.DispenseItemMapper;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import com.fuyun.pharmacy.mapper.DispensePlanMapper;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import com.fuyun.pharmacy.mapper.DrugMapper;
import com.fuyun.pharmacy.mapper.OrderMedicationMapper;
import com.fuyun.pharmacy.mapper.ReviewTaskMapper;
import com.fuyun.pharmacy.service.IBatchSelectService;
import com.fuyun.pharmacy.service.IDispensePlanService;
import com.fuyun.pharmacy.vo.DispensePlanLabelVO;
import com.fuyun.pharmacy.vo.DispensePlanReturnableVO;
import com.fuyun.pharmacy.vo.DispensePlanVO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 住院摆药计划服务实现（FU-M06-05，P2 PR-3 Task 8）：生成域（APPROVED 前置+频次分解+
 * plan_type 判定+uk 幂等）、摆药流五步（pick 预校验+排批/verify 双签+贴签核对/issue 出库
 * 落调剂行与库存三连/deliver 时间线半步/receive 签收 CAS+completed 事件住院四字段全量载荷）、
 * 查询面（分页+PIVAS 贴签数据面）、住院退药（回补+returned 事件）与停嘱/出院终清作废。
 *
 * <p>先实测裁决（Task 8 dispatch §3）落地点：①库存面复用门诊既有形态——IBatchSelectService
 * FEFO 选批 + DrugBatchMapper 条件更新（lockQuantity/deductLocked/restock）+ StockLedger
 * 流水（ISSUE 负行/RETURN_RESTOCK 正行、refDoc=计划号/调剂单号），单据维度同构零重写；
 * pick「库存预占」无中间载体列（dispense_plan 无批次列、dispense_item 在 issue 才诞生），
 * 落为 pick 只读预校验 + issue 选批-锁定-扣减同事务三连（并发安全等价，报告申报）。
 * ②freq_code 分解无 inpatient 可复用面（order_frequency 字典表跨模块禁读、无 api 端口），
 * 本类内置 qd/bid/tid/qid/qn 时点映射与 V904 种子逐字同源。③deliver 时间线承载列=
 * dispense_plan.issued_at（V1110 列注释「交付病区」语义即配送交接时点）；carrier 无落列
 * 载体以日志留痕承载。
 *
 * <p>事件纪律：completed/returned 均事务内 ApplicationEventPublisher → AFTER_COMMIT 出
 * fy.topic（PharmacyEventPublisher 承载，事务内禁 MQ 直发红线）。CAS 形态照仓内先例
 * （@Update+deleted=0+影响行数判定，0 行=并发被抢/状态违例 PH-1024 显式拒绝；终清消费位
 * casCancel 0 行=幂等跳过不上抛）。装配归 PharmacyWebConfig @Import。
 * 批量写纪律（A.4.3-16）：明细/流水收集后批语句落库（Db.saveBatch/updateBatchById）；
 * 0 行防线条件更新逐行保留禁批量化。
 */
@Slf4j
public class DispensePlanServiceImpl extends ServiceImpl<DispensePlanMapper, DispensePlan>
        implements IDispensePlanService {

    /** 调剂单号日期段格式（与门诊 createDispense 同源） */
    private static final DateTimeFormatter DISPENSE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 计划类型字面量：单剂量（口服按患者×次） */
    private static final String PLAN_TYPE_SINGLE_DOSE = "SINGLE_DOSE";

    /** 计划类型字面量：PIVAS 静配 */
    private static final String PLAN_TYPE_PIVAS = "PIVAS";

    /** 计划类型字面量：整包 */
    private static final String PLAN_TYPE_WHOLE = "WHOLE";

    /** 长期医嘱可分解频次→给药时点映射（与 M04 V904 order_frequency 种子 time_points 逐字同源：
     * qd=08:00 / bid=08:00,16:00 / tid=08:00,12:00,16:00 / qid=+20:00 / qn=20:00；pharmacy 禁读
     * inpatient 字典表（跨模块读表禁止），内置映射承载；prn/st/未知频次无固定时点=单次面） */
    private static final Map<String, List<LocalTime>> FREQ_TIME_POINTS = Map.of(
            "qd", List.of(LocalTime.of(8, 0)),
            "bid", List.of(LocalTime.of(8, 0), LocalTime.of(16, 0)),
            "tid", List.of(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(16, 0)),
            "qid", List.of(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(16, 0), LocalTime.of(20, 0)),
            "qn", List.of(LocalTime.of(20, 0)));

    /** P1 演示库房：与门诊共用单一药房库（三级库随 P3 药房管理扩展——住院分库 P3 拆分注记） */
    public static final String STOREHOUSE_SHARED = DispenseServiceImpl.STOREHOUSE_OUTP;

    private final DispensePlanMapper planMapper;

    private final OrderMedicationMapper medicationMapper;

    private final ReviewTaskMapper reviewTaskMapper;

    private final DispenseMapper dispenseMapper;

    private final DispenseItemMapper dispenseItemMapper;

    private final DrugBatchMapper drugBatchMapper;

    private final DrugMapper drugMapper;

    /** FEFO 出库选批（pick 预校验与 issue 选批-锁定联动），非空 */
    private final IBatchSelectService batchSelectService;

    /** 摆药业务号发号器（DP 计划号/DPB 排批号），非空 */
    private final PharmacySeqGate seqGate;

    /** 应用事件发布器（receive/退药事务内发布，AFTER_COMMIT 出线），非空 */
    private final ApplicationEventPublisher events;

    /** 医嘱项明细 JSON 读写器，非空 */
    private final ObjectMapper objectMapper;

    /** 患者脱敏展示名查询（PIVAS 贴签数据面唯一消费方，patient api 只读面），非空 */
    private final PatientNameQuery patientNameQuery;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import）。
     *
     * @param planMapper          摆药计划 mapper（ServiceImpl 继承 baseMapper 同源），非空
     * @param medicationMapper    住院用药快照 mapper，非空
     * @param reviewTaskMapper    审方任务 mapper（APPROVED 前置判定），非空
     * @param dispenseMapper      调剂单 mapper（issue 落行/receive 同步/退药终态），非空
     * @param dispenseItemMapper  调剂明细 mapper（issue 落行/退药回写），非空
     * @param drugBatchMapper     批次 mapper（锁定/扣减/回补条件更新通道），非空
     * @param drugMapper          药品字典 mapper（itemCode→drugId 对照），非空
     * @param batchSelectService  FEFO 选批服务，非空
     * @param seqGate             摆药业务号发号器，非空
     * @param events              应用事件发布器，非空
     * @param objectMapper        JSON 读写器，非空
     * @param patientNameQuery    患者脱敏展示名查询端口，非空
     */
    public DispensePlanServiceImpl(
            DispensePlanMapper planMapper,
            OrderMedicationMapper medicationMapper,
            ReviewTaskMapper reviewTaskMapper,
            DispenseMapper dispenseMapper,
            DispenseItemMapper dispenseItemMapper,
            DrugBatchMapper drugBatchMapper,
            DrugMapper drugMapper,
            IBatchSelectService batchSelectService,
            PharmacySeqGate seqGate,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper,
            PatientNameQuery patientNameQuery) {
        this.planMapper = planMapper;
        this.medicationMapper = medicationMapper;
        this.reviewTaskMapper = reviewTaskMapper;
        this.dispenseMapper = dispenseMapper;
        this.dispenseItemMapper = dispenseItemMapper;
        this.drugBatchMapper = drugBatchMapper;
        this.drugMapper = drugMapper;
        this.batchSelectService = batchSelectService;
        this.seqGate = seqGate;
        this.events = events;
        this.objectMapper = objectMapper;
        this.patientNameQuery = patientNameQuery;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现口径：长期分解=次日（北京钟面）逐时点计划——与 M04 日切次日排程窗口一致；
     * 临时单次=now 即刻且同医嘱已有计划行不再新建（重复 generate 幂等收敛，输出稳定清单）。
     */
    @Override
    @Transactional
    public List<DispensePlanVO> generate(DispensePlanGenerateRequest req) {
        // 数据库读操作：医嘱快照定位（uk_medication_order_no 语义）——缺失定性 PH-1025（医嘱非住院来源）
        OrderMedication medication = medicationMapper.selectOne(
                Wrappers.<OrderMedication>lambdaQuery().eq(OrderMedication::getM04OrderNo, req.m04OrderNo()));
        if (medication == null) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_ORDER_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "摆药计划生成前置失败（住院医嘱快照不存在）：" + req.m04OrderNo());
        }
        // 数据库读操作：审方任务 APPROVED 前置判定（uk_review_medication 一快照一任务）
        ReviewTask task = reviewTaskMapper.selectOne(
                Wrappers.<ReviewTask>lambdaQuery().eq(ReviewTask::getOrderMedicationId, medication.getId()));
        if (task == null || !"APPROVED".equals(task.getStatus())) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_ORDER_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "摆药计划生成前置失败（医嘱未审方通过）：m04OrderNo=" + req.m04OrderNo() + "，reviewStatus="
                            + (task == null ? "无任务" : task.getStatus()));
        }
        List<OrderItemLine> lines = parseItems(medication.getItems());
        String planType = resolvePlanType(lines);
        // 数据库读操作：既有计划全集（uk 幂等锚——已存在时点跳过；临时单次已有行不再新建）
        List<DispensePlan> existing = planMapper.selectList(Wrappers.<DispensePlan>lambdaQuery()
                .eq(DispensePlan::getM04OrderNo, req.m04OrderNo())
                .orderByAsc(DispensePlan::getPlanTime));
        Set<OffsetDateTime> existingTimes =
                existing.stream().map(DispensePlan::getPlanTime).collect(Collectors.toSet());
        // 长期医嘱次日逐时点分解（与 M04 日切次日排程窗口同口径）；不可分解频次走临时单次分支。
        // Map.of 不可变映射 get(null) 会 NPE——临时医嘱 freqCode 为 null 先行短路守卫
        List<LocalTime> points =
                medication.getFreqCode() == null ? null : FREQ_TIME_POINTS.get(medication.getFreqCode());
        List<DispensePlan> toInsert = new ArrayList<>();
        if (points != null) {
            LocalDate nextDay = LocalDate.now(TimeConstants.HEALTHCARE_TZ).plusDays(1);
            for (LocalTime point : points) {
                OffsetDateTime planTime = LocalDateTime.of(nextDay, point)
                        .atZone(TimeConstants.HEALTHCARE_TZ)
                        .toOffsetDateTime();
                if (!existingTimes.contains(planTime)) {
                    toInsert.add(buildPlan(req, medication, planType, planTime));
                }
            }
        } else if (existing.isEmpty()) {
            // 临时/按需（null/st/prn/未知）单次即刻计划：同医嘱已有计划行不再新建（幂等收敛）
            toInsert.add(buildPlan(req, medication, planType, OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ)));
        }
        // 数据库写操作：逐计划幂等落库——语句内 ON CONFLICT DO NOTHING 撞 uk_dispense_plan_order_time
        // 部分唯一索引 0 行整行放弃（并发窗口对端已落同键行；不抛异常故物理事务不中止，同批
        // 后续插入与末尾重查正常执行——Java 侧 catch DuplicateKeyException 在 PG 下必致 25P02
        // 事务毒化，禁回退该形态）
        for (DispensePlan plan : toInsert) {
            if (planMapper.insertIgnoreOrderTimeConflict(plan) == 0) {
                log.info(
                        "摆药计划生成幂等跳过（并发窗口对端已落同医嘱同给药时点计划）：m04OrderNo={}，planTime={}",
                        plan.getM04OrderNo(),
                        plan.getPlanTime());
            }
        }
        if (toInsert.isEmpty()) {
            // 无新建（重复 generate 幂等收敛）：既有清单即最新快照，免重查
            return existing.stream().map(DispensePlanVO::from).toList();
        }
        log.info(
                "摆药计划生成完成：m04OrderNo={}，wardId={}，planType={}，freqCode={}，新建={}，既有={}",
                req.m04OrderNo(),
                req.wardId(),
                planType,
                medication.getFreqCode(),
                toInsert.size(),
                existing.size());
        // 幂等稳定输出：返回该医嘱全部未删计划（重复调用输出一致）
        List<DispensePlan> latest = planMapper.selectList(Wrappers.<DispensePlan>lambdaQuery()
                .eq(DispensePlan::getM04OrderNo, req.m04OrderNo())
                .orderByAsc(DispensePlan::getPlanTime));
        return latest.stream().map(DispensePlanVO::from).toList();
    }

    /** 计划行构造（plan_no=DP 流水；类型/时点由调用方判定） */
    private DispensePlan buildPlan(
            DispensePlanGenerateRequest req, OrderMedication medication, String planType, OffsetDateTime planTime) {
        DispensePlan plan = new DispensePlan();
        plan.setPlanNo(seqGate.nextNo("DP"));
        plan.setM04OrderNo(medication.getM04OrderNo());
        plan.setVisitId(medication.getVisitId());
        plan.setPatientId(medication.getPatientId());
        plan.setWardId(req.wardId());
        plan.setPlanType(planType);
        plan.setPlanTime(planTime);
        plan.setStatus("CREATED");
        plan.setLabelPrinted(false);
        return plan;
    }

    @Override
    @Transactional
    public void pick(String planNo) {
        DispensePlan plan = requireByNo(planNo);
        long pickedBy = contextOperatorId();
        // 数据库写操作：摆药开始 CAS（CREATED→PICKING+摆药师留痕；0 行=状态违例/并发被抢）
        if (planMapper.casPick(plan.getId(), pickedBy) != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "摆药计划状态不允许摆药：" + planNo + "，当前状态=" + plan.getStatus());
        }
        // 库存充足性预校验（FEFO 只读选批；真锁定与扣减在 issue 同事务三连承载——无中间载体列，
        // 预校验缺量即拒，CAS 随整事务回滚，避免带病进摆药中）
        List<OrderItemLine> lines = loadItemLines(plan);
        Map<String, Drug> drugs = requireDrugs(lines);
        for (OrderItemLine line : lines) {
            Drug drug = drugs.get(line.itemCode());
            DrugBatch batch = batchSelectService.selectForDispense(
                    drug.getId(), STOREHOUSE_SHARED, parseQuantity(line.quantity(), line.itemCode()));
            if (batch == null) {
                throw new BizException(
                        PharmacyErrorCode.STOCK_INSUFFICIENT,
                        HttpStatus.CONFLICT,
                        "批次可用量不足，摆药预校验被拒：itemCode=" + line.itemCode());
            }
        }
        // PIVAS 链排批号回填（DPB+yyyyMMdd+3 位——给药时间分批流水号；给药时点跨计划同批聚合
        // 由发起端按给药时点分组触发承载，本切片单计划单批号）
        if (PLAN_TYPE_PIVAS.equals(plan.getPlanType())) {
            DispensePlan patch = new DispensePlan();
            patch.setId(plan.getId());
            patch.setPivasBatchNo(seqGate.nextNo("DPB"));
            planMapper.updateById(patch);
        }
        log.info("摆药开始：planNo={}，pickedBy={}，planType={}，行数={}", planNo, pickedBy, plan.getPlanType(), lines.size());
    }

    @Override
    @Transactional
    public void verify(String planNo) {
        DispensePlan plan = requireByNo(planNo);
        long verifiedBy = contextOperatorId();
        // 双人核对第二签分权（同人双签拒——门诊 PH-1011 同源语义，V1110 verified_by 双签注释承接）
        if (plan.getPickedBy() != null && verifiedBy == plan.getPickedBy()) {
            throw new BizException(PharmacyErrorCode.DUAL_SIGN_CONFLICT, HttpStatus.CONFLICT, "摆药与核对不得同一人双签：" + planNo);
        }
        // PIVAS 链=贴签核对（label_printed 置 true——打印降级注记：贴签内容经 label 数据面出）
        boolean labelPrinted = PLAN_TYPE_PIVAS.equals(plan.getPlanType());
        if (planMapper.casVerify(plan.getId(), verifiedBy, labelPrinted) != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "摆药计划状态不允许核对：" + planNo + "，当前状态=" + plan.getStatus());
        }
        log.info("摆药核对通过：planNo={}，verifiedBy={}，labelPrinted={}", planNo, verifiedBy, labelPrinted);
    }

    @Override
    @Transactional
    public void issue(String planNo) {
        DispensePlan plan = requireByNo(planNo);
        if (!"PICKED".equals(plan.getStatus())) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "摆药计划状态不允许出库：" + planNo + "，当前状态=" + plan.getStatus());
        }
        // 出库前重申双签守卫（核对留痕缺失或同人双签=前置校验未过，PH-1024 拒绝出库）
        if (plan.getVerifiedBy() == null || plan.getVerifiedBy().equals(plan.getPickedBy())) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "出库前置守卫失败（核对留痕缺失或同人双签）：" + planNo);
        }
        // 数据库写操作：出库交接 CAS（PICKED→CHECKED；0 行=并发被抢）
        if (planMapper.casStatus(plan.getId(), "PICKED", "CHECKED") != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "出库交接并发被抢：" + planNo);
        }
        String operator = operatorText();
        List<OrderItemLine> lines = loadItemLines(plan);
        Map<String, Drug> drugs = requireDrugs(lines);
        // 库存三连先行（失败面更早：任一明细缺量/超发在调剂头行落库前拒绝，回滚面更小）——
        // 行数据收集（drug/batch/qty 三元组）后统一落库
        record IssueRow(OrderItemLine line, Drug drug, DrugBatch batch, BigDecimal qty) {}
        List<IssueRow> picked = new ArrayList<>(lines.size());
        for (OrderItemLine line : lines) {
            Drug drug = drugs.get(line.itemCode());
            BigDecimal qty = parseQuantity(line.quantity(), line.itemCode());
            // 选批-锁定-扣减同事务三连（预占载体缺位的最小等价形态：锁定后立即扣减，可用量守卫
            // 两次生效，0 行=缺量/并发超发 PH-1010 整事务回滚）
            DrugBatch batch = batchSelectService.selectForDispense(drug.getId(), STOREHOUSE_SHARED, qty);
            if (batch == null
                    || drugBatchMapper.lockQuantity(batch.getId(), qty) != 1
                    || drugBatchMapper.deductLocked(batch.getId(), qty) != 1) {
                throw new BizException(
                        PharmacyErrorCode.STOCK_INSUFFICIENT,
                        HttpStatus.CONFLICT,
                        "批次锁定扣减失败（库存不足或并发超发）：itemCode=" + line.itemCode());
            }
            picked.add(new IssueRow(line, drug, batch, qty));
        }
        // 住院调剂行（诞生即 CHECKED——前三态由计划行承载流程；住院四列填充：dispense_type 按
        // plan_type 映射/ward_id/m04_order_no/dispense_plan_no；prescription_id 落 0 占位、
        // rx_no 列承载计划号可读锚——uk_dispense_rx_active 谓词限 OUTPATIENT 不占，住院行无处方）
        Dispense dispense = new Dispense();
        dispense.setDispenseNo("D" + LocalDate.now(TimeConstants.HEALTHCARE_TZ).format(DISPENSE_DATE)
                + String.format("%06d", Math.floorMod(System.nanoTime(), 1_000_000L)));
        dispense.setDispenseType(PLAN_TYPE_PIVAS.equals(plan.getPlanType()) ? "INPATIENT_PIVA" : "INPATIENT_DOSE");
        dispense.setPrescriptionId(0L);
        dispense.setRxNo(plan.getPlanNo());
        dispense.setPatientId(plan.getPatientId());
        dispense.setVisitId(plan.getVisitId());
        dispense.setStorehouse(STOREHOUSE_SHARED);
        dispense.setStatus("CHECKED");
        dispense.setWardId(plan.getWardId());
        dispense.setM04OrderNo(plan.getM04OrderNo());
        dispense.setDispensePlanNo(plan.getPlanNo());
        // 数据库写操作：住院调剂行落库（uk_dispense_no 兜底单号唯一；库存三连已全部成功后置）
        dispenseMapper.insert(dispense);
        // 数据库写操作（收集段）：出库流水行（负数量 ISSUE 行与扣减同事务落账——红线 2 勾稽）
        // 与调剂明细行（批次效期回填——prescription_item_id 双语义承载医嘱明细 itemSeq；出库即
        // 实发 issuedQty 同值；追溯码住院摆药无逐盒采集面，空集承载——nursing 袋签码推导按行
        // 摘要回退（DispenseSignoffListener 既有回退面））
        List<StockLedger> ledgers = new ArrayList<>(picked.size());
        List<DispenseItem> itemRows = new ArrayList<>(picked.size());
        for (IssueRow row : picked) {
            StockLedger ledger = new StockLedger();
            ledger.setStorehouse(STOREHOUSE_SHARED);
            ledger.setDrugId(row.drug().getId());
            ledger.setBatchId(row.batch().getId());
            ledger.setAction("ISSUE");
            ledger.setQuantity(row.qty().negate());
            ledger.setRefDoc(planNo);
            ledger.setOperator(operator);
            ledgers.add(ledger);
            DispenseItem item = new DispenseItem();
            item.setDispenseId(dispense.getId());
            item.setPrescriptionItemId((long) row.line().itemSeq());
            item.setDrugId(row.drug().getId());
            item.setItemCode(row.line().itemCode());
            item.setRequestedQty(row.qty());
            item.setIssuedQty(row.qty());
            item.setBatchId(row.batch().getId());
            item.setBatchNo(row.batch().getBatchNo());
            item.setTraceCodes("[]");
            item.setItemStatus("NORMAL");
            itemRows.add(item);
        }
        // 数据库写操作：流水与明细一次批插（A.4.3-16 批量写纪律；任一条件更新违例已在批写前抛出）
        if (!ledgers.isEmpty()) {
            Db.saveBatch(ledgers);
            Db.saveBatch(itemRows);
        }
        log.info(
                "摆药出库交接完成：planNo={}，dispenseNo={}，dispenseType={}，行数={}，operator={}",
                planNo,
                dispense.getDispenseNo(),
                dispense.getDispenseType(),
                itemRows.size(),
                operator);
    }

    @Override
    @Transactional
    public void deliver(String planNo, String carrier) {
        DispensePlan plan = requireByNo(planNo);
        if (!"CHECKED".equals(plan.getStatus())) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "摆药计划状态不允许配送交接：" + planNo + "，当前状态=" + plan.getStatus());
        }
        // 配送交接时间线半步：CHECKED 态内 issued_at 置位不迁移状态（Spec 状态机 CHECKED→DELIVERED
        // 直迁，receive 才是 CAS 迁移点）；carrier 无落列载体以日志留痕承载（V1110 无配送列）
        if (planMapper.markDeliverHandover(plan.getId(), OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ)) != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "配送交接并发被抢：" + planNo);
        }
        log.info(
                "摆药配送交接：planNo={}，carrier={}（状态保持 CHECKED——签收归 receive 迁移）",
                planNo,
                carrier == null || carrier.isBlank() ? "未登记" : carrier);
    }

    @Override
    @Transactional
    public void receive(String planNo) {
        DispensePlan plan = requireByNo(planNo);
        // W-72：签收人=令牌身份（请求体 receivedBy 兼容保留忽略——pick:291/verify:329 同款先例）
        long receivedBy = contextOperatorId();
        // 未配送不可签收（deliver 半步为签收必要前置——issued_at 时间线缺位 PH-1026）
        if (plan.getIssuedAt() == null) {
            throw new BizException(
                    PharmacyErrorCode.WARD_RECEIVE_INVALID, HttpStatus.CONFLICT, "未配送不可签收（配送交接缺位）：" + planNo);
        }
        // 数据库写操作：病区签收 CAS（CHECKED→DELIVERED+签收人/时点随行落值；issued_at 非空谓词
        // 语句内兜底并发窗口；0 行=状态违例/并发被抢）
        OffsetDateTime deliveredAt = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        if (planMapper.markDelivered(plan.getId(), receivedBy, deliveredAt) != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "摆药计划状态不允许签收：" + planNo + "，当前状态=" + plan.getStatus());
        }
        // 住院调剂行同步迁 DELIVERED（行在 issue 同事务诞生即 CHECKED；缺行=数据不一致面显式暴露）
        Dispense dispense =
                dispenseMapper.selectOne(Wrappers.<Dispense>lambdaQuery().eq(Dispense::getDispensePlanNo, planNo));
        if (dispense == null) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_NOT_FOUND, HttpStatus.NOT_FOUND, "摆药计划已出库但调剂行缺行（数据不一致）：" + planNo);
        }
        if (dispenseMapper.casStatus(dispense.getId(), "CHECKED", "DELIVERED") != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "调剂行签收同步并发被抢：" + dispense.getDispenseNo());
        }
        // 载荷行集（批次摘要携可退净量；追溯码住院摆药空集承载——消费方袋签码按行摘要回退）
        List<DispenseItem> items = dispenseItemMapper.selectList(Wrappers.<DispenseItem>lambdaQuery()
                .eq(DispenseItem::getDispenseId, dispense.getId())
                .eq(DispenseItem::getItemStatus, "NORMAL")
                .orderByAsc(DispenseItem::getId));
        List<DispenseCompletedPayload.Line> lines = items.stream()
                .map(item -> new DispenseCompletedPayload.Line(
                        item.getItemCode(),
                        item.getBatchNo(),
                        item.getIssuedQty().toPlainString(),
                        fromJson(item.getTraceCodes())))
                .toList();
        // 事务内发应用事件（AFTER_COMMIT 出线）：id 28 + V1111 住院四字段全量载荷——M05 签收
        // 衔接（nursing DispenseSignoffListener 读 m04OrderNo/dispenseType/dispensePlanNo/lines）
        // 与 M13 占用消费；门诊既有语义位（prescriptionId/rxNo）住院行承载 null
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED,
                new DispenseCompletedPayload(
                        dispense.getDispenseNo(),
                        null,
                        null,
                        dispense.getPatientId(),
                        dispense.getVisitId(),
                        dispense.getDispenseType(),
                        lines,
                        plan.getM04OrderNo(),
                        plan.getWardId(),
                        plan.getPlanNo())));
        log.info(
                "摆药病区签收完成：planNo={}，dispenseNo={}，receivedBy={}，deliveredAt={}，lines={}",
                planNo,
                dispense.getDispenseNo(),
                receivedBy,
                deliveredAt,
                lines.size());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DispensePlanVO> page(String m04OrderNo, String wardId, String status, int page, int size) {
        // 主表查询走 ServiceImpl 内置 lambdaQuery 链式（宪法 A.4.3-13）；三过滤全可空组合
        Page<DispensePlan> result = lambdaQuery()
                .eq(m04OrderNo != null && !m04OrderNo.isBlank(), DispensePlan::getM04OrderNo, m04OrderNo)
                .eq(wardId != null && !wardId.isBlank(), DispensePlan::getWardId, wardId)
                .eq(status != null && !status.isBlank(), DispensePlan::getStatus, status)
                .orderByAsc(DispensePlan::getPlanTime)
                .page(new Page<>(page, size));
        return PageResult.of(
                result.getRecords().stream().map(DispensePlanVO::from).toList(), page, size, result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public DispensePlanLabelVO label(String planNo) {
        DispensePlan plan = requireByNo(planNo);
        if (!PLAN_TYPE_PIVAS.equals(plan.getPlanType())) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "贴签数据面仅 PIVAS 链承载：" + planNo + "，planType=" + plan.getPlanType());
        }
        // 贴签药品明细自医嘱快照投影（批次在 issue 才选定——贴签「批次」承载=排批号）
        List<OrderItemLine> lines = loadItemLines(plan);
        // 患者脱敏展示名（patient api 只读面——姓名原文不跨模块，M02 敏感红线）
        String patientName = patientNameQuery.displayNamesOf(List.of(plan.getPatientId())).stream()
                .findFirst()
                .map(PatientDisplayName::displayName)
                .orElse(null);
        return new DispensePlanLabelVO(
                plan.getPlanNo(),
                plan.getM04OrderNo(),
                plan.getPatientId(),
                patientName,
                plan.getVisitId(),
                plan.getWardId(),
                null,
                plan.getPivasBatchNo(),
                plan.getPlanTime(),
                plan.getPickedBy(),
                plan.getVerifiedBy(),
                lines.stream()
                        .map(line -> new DispensePlanLabelVO.LabelItem(
                                line.itemCode(),
                                line.itemName(),
                                line.dosage(),
                                line.unit(),
                                line.route(),
                                line.quantity()))
                        .toList());
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现口径：读面与写面同守卫同序——缺行/非 DELIVERED 判定与明细装载（orderByAsc(id)）
     * 均循 acceptInpatientReturn，保证弹窗展示行集与受理校验行集逐行对齐。
     */
    @Override
    @Transactional(readOnly = true)
    public DispensePlanReturnableVO returnable(String planNo) {
        DispensePlan plan = requireByNo(planNo);
        Dispense dispense =
                dispenseMapper.selectOne(Wrappers.<Dispense>lambdaQuery().eq(Dispense::getDispensePlanNo, planNo));
        if (dispense == null) {
            // 数据库读操作缺行守卫：与 acceptInpatientReturn 同口径显式暴露（未出库不可退）
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_NOT_FOUND, HttpStatus.NOT_FOUND, "摆药计划调剂行不存在（未出库不可退药）：" + planNo);
        }
        // 读面与写面同守卫：仅病区签收后可退（W-66 弹窗数据源与受理面一致，防已退行误读）
        if (!"DELIVERED".equals(dispense.getStatus())) {
            throw new BizException(
                    PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "住院退药状态不允许（仅病区签收后可退）：" + dispense.getDispenseNo() + "，status=" + dispense.getStatus());
        }
        // 数据库读操作：NORMAL 明细行全列（orderByAsc(id) 与 acceptInpatientReturn 同序——提交缺行校验按行对齐）
        List<DispenseItem> items = dispenseItemMapper.selectList(Wrappers.<DispenseItem>lambdaQuery()
                .eq(DispenseItem::getDispenseId, dispense.getId())
                .eq(DispenseItem::getItemStatus, "NORMAL")
                .orderByAsc(DispenseItem::getId));
        List<DispensePlanReturnableVO.ReturnableItem> lines = items.stream()
                .map(item -> new DispensePlanReturnableVO.ReturnableItem(
                        String.valueOf(item.getPrescriptionItemId()),
                        item.getItemCode(),
                        item.getBatchNo(),
                        item.getIssuedQty().toPlainString(),
                        item.getReturnedQty().toPlainString(),
                        item.getIssuedQty().subtract(item.getReturnedQty()).toPlainString()))
                .toList();
        return new DispensePlanReturnableVO(
                plan.getPlanNo(),
                dispense.getDispenseNo(),
                dispense.getStatus(),
                dispense.getPatientId(),
                dispense.getVisitId(),
                plan.getWardId(),
                lines);
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现口径：住院退药锚定=医嘱明细 itemSeq（dispense_item.prescription_item_id 双语义承载）；
     * 计划行状态不迁（V1110 词表无退药态——退药态由调剂行承载，注记）。
     */
    @Override
    @Transactional
    public void acceptInpatientReturn(DispenseReturnRequest req) {
        DispensePlan plan = requireByNo(req.dispensePlanNo());
        Dispense dispense = dispenseMapper.selectOne(
                Wrappers.<Dispense>lambdaQuery().eq(Dispense::getDispensePlanNo, req.dispensePlanNo()));
        if (dispense == null) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "摆药计划调剂行不存在（未出库不可退药）：" + req.dispensePlanNo());
        }
        // 住院退药仅 DELIVERED 可退（病区签收后实物退；未签收归 pharmacy 侧库存内退场——本切片不承载）
        if (!"DELIVERED".equals(dispense.getStatus())) {
            throw new BizException(
                    PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "住院退药状态不允许（仅病区签收后可退）：" + dispense.getDispenseNo() + "，status=" + dispense.getStatus());
        }
        List<DispenseItem> items = dispenseItemMapper.selectList(Wrappers.<DispenseItem>lambdaQuery()
                .eq(DispenseItem::getDispenseId, dispense.getId())
                .eq(DispenseItem::getItemStatus, "NORMAL")
                .orderByAsc(DispenseItem::getId));
        String operator = operatorText();
        boolean allReturned = true;
        List<StockLedger> ledgers = new ArrayList<>(items.size());
        List<DispenseItem> itemPatches = new ArrayList<>(items.size());
        List<DispenseReturnedPayload.Line> summary = new ArrayList<>(items.size());
        for (DispenseItem item : items) {
            DispenseReturnRequest.InpatientReturnLine line = req.returnLines() == null
                    ? null
                    : req.returnLines().stream()
                            .filter(l ->
                                    String.valueOf(item.getPrescriptionItemId()).equals(l.itemSeq()))
                            .findFirst()
                            .orElse(null);
            if (line == null) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.BAD_REQUEST,
                        "住院退药缺行：itemSeq=" + item.getPrescriptionItemId());
            }
            BigDecimal returnQty = parseReturnQuantity(line.returnQuantity());
            BigDecimal returnable = item.getIssuedQty().subtract(item.getReturnedQty());
            // 数量守卫：累计退药不得超实发（PH-1013）
            if (returnQty.signum() <= 0 || returnQty.compareTo(returnable) > 0) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "住院退药数量超可退余额：itemCode=" + item.getItemCode());
            }
            List<String> issuedTraces = fromJson(item.getTraceCodes());
            // 追溯码核验（PH-1012 防回流药）：退药码集须为发药采集码集子集——住院摆药采集为空集，
            // 非空退药码即拒（未采集面的防回流语义收紧承载）
            if (line.traceCodes() != null && !issuedTraces.containsAll(line.traceCodes())) {
                throw new BizException(
                        PharmacyErrorCode.TRACE_CODE_MISMATCH,
                        HttpStatus.CONFLICT,
                        "追溯码与摆药记录不一致（防回流药核验拒）：itemCode=" + item.getItemCode());
            }
            // 数据库写操作：批次回补（回补同一批次保批号勾稽——门诊退药同款形态）
            if (drugBatchMapper.restock(item.getBatchId(), returnQty) != 1) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "住院退药批次回补失败：batchId=" + item.getBatchId());
            }
            StockLedger ledger = new StockLedger();
            ledger.setStorehouse(dispense.getStorehouse());
            ledger.setDrugId(item.getDrugId());
            ledger.setBatchId(item.getBatchId());
            ledger.setAction("RETURN_RESTOCK");
            ledger.setQuantity(returnQty);
            ledger.setRefDoc(dispense.getDispenseNo());
            ledger.setOperator(operator);
            ledgers.add(ledger);
            BigDecimal returnedTotal = item.getReturnedQty().add(returnQty);
            DispenseItem patch = new DispenseItem();
            patch.setId(item.getId());
            patch.setReturnedQty(returnedTotal);
            itemPatches.add(patch);
            summary.add(new DispenseReturnedPayload.Line(
                    item.getItemCode(), item.getBatchNo(), returnQty.toPlainString(), issuedTraces));
            if (returnedTotal.compareTo(item.getIssuedQty()) != 0) {
                allReturned = false;
            }
        }
        if (!ledgers.isEmpty()) {
            Db.saveBatch(ledgers);
            Db.updateBatchById(itemPatches);
        }
        // 调剂行退药终态（计划行不迁——V1110 词表无退药态，退药态由调剂行承载）
        String target = allReturned ? "FULL_RETURNED" : "PART_RETURNED";
        if (dispenseMapper.casStatus(dispense.getId(), "DELIVERED", target) != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "住院退药终态迁移并发被抢：" + dispense.getDispenseNo());
        }
        // 事务内发应用事件（id 29 冻结载荷；住院行 prescriptionId/rxNo 承载 null）
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_DISPENSE_RETURNED,
                new DispenseReturnedPayload(
                        dispense.getDispenseNo(),
                        null,
                        null,
                        dispense.getPatientId(),
                        dispense.getVisitId(),
                        allReturned,
                        summary)));
        log.info(
                "住院退药受理完成：planNo={}，dispenseNo={}，allReturned={}，lines={}，operator={}",
                plan.getPlanNo(),
                dispense.getDispenseNo(),
                allReturned,
                summary.size(),
                operator);
    }

    @Override
    @Transactional
    public void cancelByOrderTerminal(String m04OrderNo, String stopReason) {
        // 数据库读操作：该医嘱未摆药计划（CREATED/PICKING——已摆出不属未摆药作废面）
        List<DispensePlan> pendings = planMapper.selectList(Wrappers.<DispensePlan>lambdaQuery()
                .eq(DispensePlan::getM04OrderNo, m04OrderNo)
                .in(DispensePlan::getStatus, "CREATED", "PICKING"));
        cancelPendingPlans(pendings, stopReason);
        log.info("停嘱联动作废完成：m04OrderNo={}，作废={}，reason={}", m04OrderNo, pendings.size(), stopReason);
    }

    @Override
    @Transactional
    public void cancelByVisitDischarge(String visitId) {
        List<DispensePlan> pendings = planMapper.selectList(Wrappers.<DispensePlan>lambdaQuery()
                .eq(DispensePlan::getVisitId, visitId)
                .in(DispensePlan::getStatus, "CREATED", "PICKING"));
        cancelPendingPlans(pendings, "出院终清作废");
        // 已摆未用（PICKED/CHECKED/DELIVERED）不作废——退药人工发起（不自动回补，提示留痕）
        List<DispensePlan> used = planMapper.selectList(Wrappers.<DispensePlan>lambdaQuery()
                .eq(DispensePlan::getVisitId, visitId)
                .in(DispensePlan::getStatus, "PICKED", "CHECKED", "DELIVERED"));
        if (!used.isEmpty()) {
            log.warn(
                    "出院终清发现已摆未用计划（退药人工发起，不自动回补）：visitId={}，planNos={}",
                    visitId,
                    used.stream().map(DispensePlan::getPlanNo).toList());
        }
        log.info("出院终清联动作废完成：visitId={}，作废={}，已摆未用={}", visitId, pendings.size(), used.size());
    }

    /** 未摆药计划批量作废（消费位纪律：CAS 0 行=已终态幂等跳过不上抛） */
    private void cancelPendingPlans(List<DispensePlan> pendings, String reason) {
        for (DispensePlan plan : pendings) {
            if (planMapper.casCancel(plan.getId(), reason) != 1) {
                log.info("摆药计划作废幂等跳过（已终态或已摆出）：planNo={}，status={}", plan.getPlanNo(), plan.getStatus());
            }
        }
    }

    /**
     * 按计划号定位摆药计划（uk 唯一）。
     *
     * @param planNo 摆药计划号，非空
     * @return 摆药计划行，非空
     * @throws BizException PH-1023（404，dispense_plan_no 无命中）
     */
    private DispensePlan requireByNo(String planNo) {
        // 主表查询走 ServiceImpl 内置 lambdaQuery 链式（宪法 A.4.3-13）
        DispensePlan plan = lambdaQuery().eq(DispensePlan::getPlanNo, planNo).one();
        if (plan == null) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_PLAN_NOT_FOUND, HttpStatus.NOT_FOUND, "摆药计划不存在：" + planNo);
        }
        return plan;
    }

    /** 计划来源医嘱快照定位（缺行=数据不一致面——计划行存在快照被逻辑删，显式暴露） */
    private OrderMedication requireMedication(DispensePlan plan) {
        OrderMedication medication = medicationMapper.selectOne(
                Wrappers.<OrderMedication>lambdaQuery().eq(OrderMedication::getM04OrderNo, plan.getM04OrderNo()));
        if (medication == null) {
            throw new BizException(
                    PharmacyErrorCode.MEDICATION_ORDER_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "摆药计划医嘱快照缺行（数据不一致）：" + plan.getM04OrderNo());
        }
        return medication;
    }

    /** 装载计划医嘱项明细（快照 JSON 解析共享面：pick 预校验/issue 落行/label 投影） */
    private List<OrderItemLine> loadItemLines(DispensePlan plan) {
        return parseItems(requireMedication(plan).getItems());
    }

    /**
     * 医嘱项明细 JSON 快照解析（V1000 契约：itemSeq/itemCode/itemName/dosage/unit/route/
     * quantity/itemType 八字段数组文本）。
     *
     * @param itemsJson 快照 JSON 数组文本，非空
     * @return 明细行清单，非空（空数组=纯嘱托类 drug 行合法空面）
     * @throws IllegalStateException 非法 JSON（脏数据显式暴露禁静默吞）
     */
    private List<OrderItemLine> parseItems(String itemsJson) {
        try {
            return objectMapper.readValue(itemsJson, new TypeReference<List<OrderItemLine>>() {});
        } catch (JsonProcessingException e) {
            // EX-19 C 类留痕：DB 脏数据防御（非用户输入路径），ISE 显式暴露禁静默吞
            throw new IllegalStateException("医嘱项明细快照数据损坏（非 JSON 数组）：items=" + itemsJson, e);
        }
    }

    /**
     * 计划类型判定：任一行用法含静脉（「静」族字）或 PIVAS 标记→PIVAS；否则任一行含口服→
     * SINGLE_DOSE（口服按患者×次单剂量摆药）；其余 WHOLE 整包。
     */
    private static String resolvePlanType(List<OrderItemLine> lines) {
        boolean anyIntravenous = lines.stream()
                .anyMatch(line -> line.route() != null
                        && (line.route().contains("静")
                                || line.route().toUpperCase().contains("PIVAS")));
        if (anyIntravenous) {
            return PLAN_TYPE_PIVAS;
        }
        boolean anyOral = lines.stream()
                .anyMatch(line -> line.route() != null && line.route().contains("口服"));
        return anyOral ? PLAN_TYPE_SINGLE_DOSE : PLAN_TYPE_WHOLE;
    }

    /**
     * 药品字典批量对照（itemCode 键集一次 IN 批查——A.4.3-14 批查化；缺对照 PH-1001 显式拒）。
     */
    private Map<String, Drug> requireDrugs(List<OrderItemLine> lines) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        List<String> itemCodes =
                lines.stream().map(OrderItemLine::itemCode).distinct().toList();
        Map<String, Drug> drugs =
                drugMapper.selectList(Wrappers.<Drug>lambdaQuery().in(Drug::getItemCode, itemCodes)).stream()
                        .collect(Collectors.toMap(
                                Drug::getItemCode,
                                Function.identity(),
                                (first, duplicate) -> first,
                                LinkedHashMap::new));
        for (String itemCode : itemCodes) {
            if (!drugs.containsKey(itemCode)) {
                throw new BizException(
                        PharmacyErrorCode.DRUG_NOT_FOUND, HttpStatus.NOT_FOUND, "药品字典无对照（收费项目未对照药品）：" + itemCode);
            }
        }
        return drugs;
    }

    /**
     * 摆药数量解析（医嘱快照 quantity DECIMAL string 承载）。
     *
     * @throws BizException PH-1016（400）：非数字串
     */
    private static BigDecimal parseQuantity(String quantity, String itemCode) {
        try {
            return new BigDecimal(quantity);
        } catch (NumberFormatException e) {
            throw new BizException(
                    PharmacyErrorCode.NUMERIC_FIELD_MALFORMED,
                    HttpStatus.BAD_REQUEST,
                    "摆药数量须为数字串：itemCode=" + itemCode + "，quantity=" + quantity);
        }
    }

    /**
     * 退药数量解析守卫（PH-1016，W-22⑦ 门诊同源形态；scale≤3 守卫为 PR-4B 五路评审 C-F1 补钉）。
     *
     * <p>scale 上限 3 与 dispense_item.issued_qty/returned_qty 列 DECIMAL(12,3) 精度对齐：超 3 位
     * 小数会被 PG 静默舍入，致 PART/FULL 终态判定与 returned_qty、事件载荷勾稽漂移，须在应用层
     * 显式拒绝（400）。
     *
     * @param returnQuantity 退药数量 DECIMAL string，来源前端退药弹窗逐行录入
     * @return 解析后的退药数量（小数位 ≤3，尾零形态如 "1.500" 放行）
     * @throws BizException PH-1016（400）：非数字串；或 stripTrailingZeros 后小数位超 3 位
     */
    private static BigDecimal parseReturnQuantity(String returnQuantity) {
        BigDecimal qty;
        try {
            qty = new BigDecimal(returnQuantity);
        } catch (NumberFormatException e) {
            throw new BizException(
                    PharmacyErrorCode.NUMERIC_FIELD_MALFORMED, HttpStatus.BAD_REQUEST, "退药数量须为数字串：" + returnQuantity);
        }
        // scale 守卫（评审 C-F1）：剥尾零后小数位 >3 即拒——"1.500"（有效 1 位）放行、"0.1234" 拒
        if (qty.stripTrailingZeros().scale() > 3) {
            throw new BizException(
                    PharmacyErrorCode.NUMERIC_FIELD_MALFORMED,
                    HttpStatus.BAD_REQUEST,
                    "退药数量小数位超限（最多 3 位，列 DECIMAL(12,3)）：" + returnQuantity);
        }
        return qty;
    }

    /**
     * 操作者员工 ID 解析（picked_by/verified_by BIGINT 落值——nursing contextOperatorId 同款
     * 形态：非数字标识 PH-1016 拒，禁 NumberFormatException 直穿 500）。
     */
    private static long contextOperatorId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    PharmacyErrorCode.NUMERIC_FIELD_MALFORMED,
                    HttpStatus.BAD_REQUEST,
                    "操作者标识缺失或非数字（无法定位摆药/核对主体）：" + operator);
        }
        return Long.parseLong(operator);
    }

    /** 操作者取值（无登录上下文回退 system，与审计列默认同源） */
    private static String operatorText() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? "system" : operator;
    }

    /**
     * 行级追溯码 JSON 文本还原码集（receive 载荷与退药核验共用语义）。
     *
     * @throws IllegalStateException 非法 JSON（脏数据显式暴露禁静默吞码）
     */
    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("调剂明细追溯码数据损坏（非 JSON 数组）：traceCodes=" + json, e);
        }
    }

    /**
     * 医嘱项明细快照行（V1000 items JSON 契约八字段——record 反序列化承载；quantity 为 DECIMAL
     * string 承载）。归 internal 使用面（JSON 契约镜像），包私有控制可见性。
     */
    record OrderItemLine(
            int itemSeq,
            String itemCode,
            String itemName,
            String dosage,
            String unit,
            String route,
            String quantity,
            String itemType) {}
}
