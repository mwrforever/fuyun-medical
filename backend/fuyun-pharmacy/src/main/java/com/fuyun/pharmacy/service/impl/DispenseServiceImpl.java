package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.DispenseCompletedPayload;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.dto.PickLine;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import com.fuyun.pharmacy.entity.DrugBatch;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.PrescriptionItem;
import com.fuyun.pharmacy.entity.StockLedger;
import com.fuyun.pharmacy.internal.PharmacyDomainEvent;
import com.fuyun.pharmacy.mapper.DispenseItemMapper;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import com.fuyun.pharmacy.mapper.PrescriptionItemMapper;
import com.fuyun.pharmacy.mapper.PrescriptionMapper;
import com.fuyun.pharmacy.mapper.StockLedgerMapper;
import com.fuyun.pharmacy.service.IBatchSelectService;
import com.fuyun.pharmacy.service.IDispenseService;
import com.fuyun.pharmacy.vo.DispenseVO;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发药服务实现：放行链（charged→PENDING_DISPENSE+入队）、费用链（fee.created→PENDING_FEE）与
 * 调剂三段闭环（pick FEFO 批次锁定+追溯码采集 → verify 双签核对 → issue 发药签名+批次扣减+
 * 出库流水+completed 事件）。三段状态机 CAS 收口：0 行=并发被抢/状态违例一律显式拒绝（无负库存，
 * 未锁先发即 PH-1010）；双签分权（调配/核对同人 PH-1011，Spec :226）为法定留痕硬守卫。
 * 事件消费幂等双层：eventId 构件幂等之外，业务级「CAS 0 行→重读定性：已达目标态幂等跳过、
 * 其余状态 warn 跳过不上抛」（charged 重复投递仅放行一次，Spec §10 异常项）。uk_dispense_rx_active
 * 兜底防重复建单。装配归 PharmacyWebConfig @Import。
 */
@Slf4j
public class DispenseServiceImpl extends ServiceImpl<DispenseMapper, Dispense> implements IDispenseService {

    /** rx_no 日期段格式（与处方侧同源） */
    private static final DateTimeFormatter RX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** P1 演示库房：单一门诊药房（三级库随 P3 药房管理扩展） */
    public static final String STOREHOUSE_OUTP = "OUTP_PHARM";

    /** 调剂单 mapper（与继承 baseMapper 同源；三段 CAS/留痕显式引用），非空 */
    private final DispenseMapper dispenseMapper;

    private final DispenseItemMapper dispenseItemMapper;

    private final DrugBatchMapper drugBatchMapper;

    private final StockLedgerMapper stockLedgerMapper;

    private final PrescriptionMapper prescriptionMapper;

    private final PrescriptionItemMapper prescriptionItemMapper;

    /** FEFO 出库选批（pick 选批与条件锁定锁定防线的前半段），非空 */
    private final IBatchSelectService batchSelectService;

    /** 应用事件发布器（issue 事务内发布 completed，AFTER_COMMIT 出 fy.topic），非空 */
    private final ApplicationEventPublisher events;

    /** 追溯码 JSON 读写（dispense_item.trace_codes 文本承载），非空 */
    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import；Task 6 起扩九参——batchSelectService/events/
     * objectMapper 承载调剂三段依赖）。
     *
     * @param dispenseMapper         调剂单 mapper（ServiceImpl 继承 baseMapper 同源），非空
     * @param dispenseItemMapper     调剂明细 mapper，非空
     * @param drugBatchMapper        批次 mapper（锁定/扣减条件更新通道），非空
     * @param stockLedgerMapper      库存流水 mapper（只增 INSERT 通道），非空
     * @param prescriptionMapper     处方 mapper，非空
     * @param prescriptionItemMapper 处方明细 mapper，非空
     * @param batchSelectService     FEFO 选批服务，非空
     * @param events                 应用事件发布器，非空
     * @param objectMapper           追溯码 JSON 读写器，非空
     */
    public DispenseServiceImpl(
            DispenseMapper dispenseMapper,
            DispenseItemMapper dispenseItemMapper,
            DrugBatchMapper drugBatchMapper,
            StockLedgerMapper stockLedgerMapper,
            PrescriptionMapper prescriptionMapper,
            PrescriptionItemMapper prescriptionItemMapper,
            IBatchSelectService batchSelectService,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper) {
        this.dispenseMapper = dispenseMapper;
        this.dispenseItemMapper = dispenseItemMapper;
        this.drugBatchMapper = drugBatchMapper;
        this.stockLedgerMapper = stockLedgerMapper;
        this.prescriptionMapper = prescriptionMapper;
        this.prescriptionItemMapper = prescriptionItemMapper;
        this.batchSelectService = batchSelectService;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void releaseByVisit(String visitId) {
        // 数据库读操作：该就诊待放行处方（门诊/急诊；charged 为门诊/急诊唯一权威放行通道）
        List<Prescription> rxs = prescriptionMapper.selectList(Wrappers.<Prescription>lambdaQuery()
                .eq(Prescription::getVisitId, visitId)
                .eq(Prescription::getStatus, "PENDING_FEE")
                .in(Prescription::getRxType, "OUTPATIENT", "EMERGENCY")
                .orderByAsc(Prescription::getId));
        for (Prescription rx : rxs) {
            // 数据库写操作：CAS 放行（0 行=并发放行/状态漂移，重读定性幂等或跳过）
            if (prescriptionMapper.casStatus(rx.getId(), "PENDING_FEE", "PENDING_DISPENSE") != 1) {
                Prescription latest = prescriptionMapper.selectById(rx.getId());
                String latestStatus = latest == null ? "UNKNOWN" : latest.getStatus();
                if ("PENDING_DISPENSE".equals(latestStatus) || "DISPENSING".equals(latestStatus)) {
                    log.info("缴费放行幂等跳过（已放行）：rxNo={}，status={}", rx.getRxNo(), latestStatus);
                } else {
                    log.warn("缴费放行跳过（状态漂移）：rxNo={}，status={}", rx.getRxNo(), latestStatus);
                }
                continue;
            }
            createDispense(rx);
        }
    }

    @Override
    @Transactional
    public void markPendingFee(String billingKey) {
        String[] parts = billingKey.split("\\|");
        // 处方通道守卫：第三段触发型必须 PRESCRIPTION_EFFECTIVE（开单/日切等通道与本模块无关）
        if (parts.length < 3 || !"PRESCRIPTION_EFFECTIVE".equals(parts[2])) {
            return;
        }
        String rxNo = parts[1];
        Prescription rx = prescriptionMapper.selectOne(
                Wrappers.<Prescription>lambdaQuery().eq(Prescription::getRxNo, rxNo));
        if (rx == null) {
            log.warn("费用回执无法定位处方（脏数据留痕不阻断通道）：rxNo={}", rxNo);
            return;
        }
        // 数据库写操作：CAS 迁移 APPROVED→PENDING_FEE（R2-14：由 billing.fee.created 驱动）
        if (prescriptionMapper.casStatus(rx.getId(), "APPROVED", "PENDING_FEE") != 1) {
            Prescription latest = prescriptionMapper.selectById(rx.getId());
            String latestStatus = latest == null ? "UNKNOWN" : latest.getStatus();
            if ("PENDING_FEE".equals(latestStatus)) {
                log.info("费用回执幂等跳过（已迁移）：rxNo={}", rxNo);
            } else {
                log.warn("费用回执跳过（状态漂移）：rxNo={}，status={}", rxNo, latestStatus);
            }
        }
    }

    @Override
    @Transactional
    public void pick(String dispenseNo, List<PickLine> lines) {
        Dispense d = requireByNo(dispenseNo);
        Prescription rx = requireRx(d.getRxNo());
        // 数据库写操作：单据与处方双 CAS 进配药中（Spec :132/:134 DISPENSING 双行迁移）
        if (dispenseMapper.casStatus(d.getId(), "CREATED", "PICKING") != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "调剂单状态不允许配药：" + dispenseNo);
        }
        if (prescriptionMapper.casStatus(rx.getId(), "PENDING_DISPENSE", "DISPENSING") != 1) {
            throw new BizException(
                    PharmacyErrorCode.PRESCRIPTION_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "处方状态不允许配药：" + d.getRxNo());
        }
        List<DispenseItem> items = dispenseItemMapper.selectList(Wrappers.<DispenseItem>lambdaQuery()
                .eq(DispenseItem::getDispenseId, d.getId())
                .eq(DispenseItem::getItemStatus, "NORMAL")
                .orderByAsc(DispenseItem::getId));
        String operator = OperatorContextHolder.get();
        for (DispenseItem item : items) {
            PickLine line = lines.stream()
                    .filter(l -> String.valueOf(item.getPrescriptionItemId()).equals(l.prescriptionItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BizException(
                            PharmacyErrorCode.PRESCRIPTION_LINE_INVALID,
                            HttpStatus.BAD_REQUEST,
                            "配药采集缺行：prescriptionItemId=" + item.getPrescriptionItemId()));
            if (line.traceCodes() == null || line.traceCodes().isEmpty()) {
                // 医保「无码不结」口径：逐码采集为发药前置（2025-07 起）
                throw new BizException(
                        PharmacyErrorCode.PRESCRIPTION_LINE_INVALID,
                        HttpStatus.BAD_REQUEST,
                        "追溯码逐码采集为空（无码不结）：itemCode=" + item.getItemCode());
            }
            // 选批 + 条件锁定（防并发超发硬防线；选批失败/锁定 0 行一律 PH-1010 整事务回滚）
            DrugBatch batch =
                    batchSelectService.selectForDispense(item.getDrugId(), d.getStorehouse(), item.getRequestedQty());
            if (batch == null || drugBatchMapper.lockQuantity(batch.getId(), item.getRequestedQty()) != 1) {
                throw new BizException(
                        PharmacyErrorCode.STOCK_INSUFFICIENT,
                        HttpStatus.CONFLICT,
                        "批次可用量不足，配药被拒：drugId=" + item.getDrugId());
            }
            // 数据库写操作：批次与逐码采集回填明细行（追溯码 JSON 文本承载）
            item.setBatchId(batch.getId());
            item.setBatchNo(batch.getBatchNo());
            item.setTraceCodes(toJson(line.traceCodes()));
            dispenseItemMapper.updateById(item);
        }
        d.setPicker(operator);
        dispenseMapper.updateById(d);
        log.info("配药锁定完成：dispenseNo={}，picker={}，行数={}", dispenseNo, operator, items.size());
    }

    @Override
    @Transactional
    public void verify(String dispenseNo) {
        Dispense d = requireByNo(dispenseNo);
        String operator = OperatorContextHolder.get();
        // 双签分权（Spec :226）：核对人不得为调配人（后端硬守卫，前端按钮启停为辅助面）
        if (operator.equals(d.getPicker())) {
            throw new BizException(
                    PharmacyErrorCode.DUAL_SIGN_CONFLICT, HttpStatus.CONFLICT, "同一处方调配与核对不得同一人双签：" + dispenseNo);
        }
        if (dispenseMapper.casStatus(d.getId(), "PICKING", "PICKED") != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "调剂单状态不允许核对：" + dispenseNo);
        }
        d.setVerifier(operator);
        dispenseMapper.updateById(d);
        log.info("扫码核对通过：dispenseNo={}，verifier={}", dispenseNo, operator);
    }

    @Override
    @Transactional
    public void issue(String dispenseNo) {
        Dispense d = requireByNo(dispenseNo);
        if (!"PICKED".equals(d.getStatus())) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "调剂单状态不允许发药：" + dispenseNo);
        }
        // 发药签名前重申双签守卫（核对留痕缺失或同人双签=前置校验未过，一并定性 PH-1009 拒发）
        if (d.getVerifier() == null || d.getVerifier().equals(d.getPicker())) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "发药前置守卫失败（核对留痕缺失或同人双签）：" + dispenseNo);
        }
        String operator = OperatorContextHolder.get();
        // 数据库写操作：发药签名 CAS（核对/发药人/时刻一并落行）
        if (dispenseMapper.casIssue(d.getId(), operator, OffsetDateTime.now()) != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "发药签名并发被抢：" + dispenseNo);
        }
        Prescription rx = requireRx(d.getRxNo());
        List<DispenseItem> items = dispenseItemMapper.selectList(Wrappers.<DispenseItem>lambdaQuery()
                .eq(DispenseItem::getDispenseId, d.getId())
                .eq(DispenseItem::getItemStatus, "NORMAL")
                .orderByAsc(DispenseItem::getId));
        List<DispenseCompletedPayload.Line> summary = new ArrayList<>(items.size());
        for (DispenseItem item : items) {
            // 数据库写操作：批次锁定转扣减（0 行=违例整事务回滚）+ 出库流水（红线 2：与扣减同事务）
            if (drugBatchMapper.deductLocked(item.getBatchId(), item.getRequestedQty()) != 1) {
                throw new BizException(
                        PharmacyErrorCode.STOCK_INSUFFICIENT,
                        HttpStatus.CONFLICT,
                        "批次锁定扣减失败（未锁先发违例）：batchId=" + item.getBatchId());
            }
            StockLedger ledger = new StockLedger();
            ledger.setStorehouse(d.getStorehouse());
            ledger.setDrugId(item.getDrugId());
            ledger.setBatchId(item.getBatchId());
            ledger.setAction("ISSUE");
            ledger.setQuantity(item.getRequestedQty().negate());
            ledger.setRefDoc(dispenseNo);
            ledger.setOperator(operator);
            stockLedgerMapper.insert(ledger);
            item.setIssuedQty(item.getRequestedQty());
            dispenseItemMapper.updateById(item);
            summary.add(new DispenseCompletedPayload.Line(
                    item.getItemCode(),
                    item.getBatchNo(),
                    item.getRequestedQty().toPlainString(),
                    fromJson(item.getTraceCodes())));
        }
        // 数据库写操作：处方终态基点迁移 DISPENSED
        if (prescriptionMapper.casStatus(rx.getId(), "DISPENSING", "DISPENSED") != 1) {
            throw new BizException(
                    PharmacyErrorCode.PRESCRIPTION_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "处方状态不允许发药收敛：" + d.getRxNo());
        }
        // 事务内发应用事件（AFTER_COMMIT 出线）：批次摘要携 M03/billing 消费面
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED,
                new DispenseCompletedPayload(
                        dispenseNo,
                        d.getRxNo(),
                        d.getRxNo(),
                        d.getPatientId(),
                        d.getVisitId(),
                        d.getDispenseType(),
                        summary)));
        log.info("发药签名完成：dispenseNo={}，issuer={}，rxNo={}，行数={}", dispenseNo, operator, d.getRxNo(), items.size());
    }

    @Override
    @Transactional(readOnly = true)
    public DispenseVO getByRxNo(String rxNo) {
        // 数据库读操作：一处方一张活动单（uk_dispense_rx_active），按 rx_no 定位；无单返回 null（调用方组空集）
        Dispense d = baseMapper.selectOne(Wrappers.<Dispense>lambdaQuery().eq(Dispense::getRxNo, rxNo));
        if (d == null) {
            return null;
        }
        // 数据库读操作：随行装载明细（全状态行，itemStatus 随行直出供工作台辨识退场行）
        List<DispenseItem> items = dispenseItemMapper.selectList(Wrappers.<DispenseItem>lambdaQuery()
                .eq(DispenseItem::getDispenseId, d.getId())
                .orderByAsc(DispenseItem::getId));
        return DispenseVO.from(d, items);
    }

    /** 建 CREATED 发药单与明细入队（uk_dispense_rx_active 兜底重复建单） */
    private void createDispense(Prescription rx) {
        Dispense dispense = new Dispense();
        dispense.setDispenseNo("D" + LocalDate.now().format(RX_DATE)
                + String.format("%06d", Math.floorMod(System.nanoTime(), 1_000_000L)));
        dispense.setDispenseType("OUTPATIENT");
        dispense.setPrescriptionId(rx.getId());
        dispense.setRxNo(rx.getRxNo());
        dispense.setPatientId(rx.getPatientId());
        dispense.setVisitId(rx.getVisitId());
        dispense.setStorehouse(STOREHOUSE_OUTP);
        dispense.setStatus("CREATED");
        // 数据库写操作：发药单落库（唯一索引兜底 charged 重投并发建单）
        baseMapper.insert(dispense);
        // 数据库读操作+写操作：处方明细快照入队（应发数=处方数量，批次/追溯码随 pick 回填）
        List<PrescriptionItem> items = prescriptionItemMapper.selectList(Wrappers.<PrescriptionItem>lambdaQuery()
                .eq(PrescriptionItem::getPrescriptionId, rx.getId())
                .eq(PrescriptionItem::getStatus, "NORMAL")
                .orderByAsc(PrescriptionItem::getId));
        for (PrescriptionItem item : items) {
            DispenseItem row = new DispenseItem();
            row.setDispenseId(dispense.getId());
            row.setPrescriptionItemId(item.getId());
            row.setDrugId(item.getDrugId());
            row.setItemCode(item.getItemCode());
            row.setRequestedQty(item.getQuantity());
            row.setItemStatus("NORMAL");
            dispenseItemMapper.insert(row);
        }
        log.info("缴费放行入队：rxNo={}，dispenseNo={}，明细数={}", rx.getRxNo(), dispense.getDispenseNo(), items.size());
    }

    /**
     * 按单号定位调剂单（uk 唯一）。
     *
     * @param dispenseNo 调剂单号，非空
     * @return 调剂单行，非空
     * @throws BizException PH-1008（404，dispense_no 无命中）
     */
    private Dispense requireByNo(String dispenseNo) {
        Dispense d = baseMapper.selectOne(Wrappers.<Dispense>lambdaQuery().eq(Dispense::getDispenseNo, dispenseNo));
        if (d == null) {
            throw new BizException(PharmacyErrorCode.DISPENSE_NOT_FOUND, HttpStatus.NOT_FOUND, "调剂单不存在：" + dispenseNo);
        }
        return d;
    }

    /**
     * 按处方号定位处方（与 Task 3 cancel 定位形态同源）。
     *
     * @param rxNo 处方号，非空
     * @return 处方行，非空
     * @throws BizException PH-1004（404，rx_no 无命中）
     */
    private Prescription requireRx(String rxNo) {
        Prescription rx = prescriptionMapper.selectOne(
                Wrappers.<Prescription>lambdaQuery().eq(Prescription::getRxNo, rxNo));
        if (rx == null) {
            throw new BizException(PharmacyErrorCode.PRESCRIPTION_NOT_FOUND, HttpStatus.NOT_FOUND, "处方不存在：" + rxNo);
        }
        return rx;
    }

    /**
     * 追溯码集 → JSON 数组文本（valueToTree 不抛受检异常，List&lt;String&gt; 序列化无失败面）。
     *
     * @param codes 追溯码集，非空
     * @return JSON 数组文本，非空
     */
    private String toJson(List<String> codes) {
        return objectMapper.valueToTree(codes).toString();
    }

    /**
     * 行级追溯码 JSON 文本还原码集（issue 事件携出与 VO 回显共用语义）。
     *
     * @param json JSON 数组文本，非空
     * @return 码集，非空
     * @throws IllegalStateException 非法 JSON（脏数据显式暴露禁静默吞码——追溯码为防回流核验依据）
     */
    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("调剂明细追溯码数据损坏（非 JSON 数组）：traceCodes=" + json, e);
        }
    }
}
