package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.DispenseCompletedPayload;
import com.fuyun.pharmacy.api.DispenseReturnedPayload;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.cache.PharmacyMasterDataCache;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.dto.DispenseReturnRequest;
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
import com.fuyun.pharmacy.vo.OccupancyVO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发药服务实现：放行链（charged→PENDING_DISPENSE+入队）、费用链（fee.created→PENDING_FEE）、
 * 调剂三段闭环（pick FEFO 批次锁定+追溯码采集 → verify 双签核对 → issue 发药签名+批次扣减+
 * 出库流水+completed 事件）与退药受理两时点（ISSUED_RETURN 实物退批次回补+处方明细退药
 * 累计回写+returned 事件 / DISPENSING_CANCEL 发药中明细退场释放锁定）、refund.approved 终态收敛与执行占用查询
 * （读侧经主数据缓存患者归一，供 M13 位）。状态机 CAS 收口：
 * 0 行=并发被抢/状态违例一律显式拒绝（无负库存，未锁先发即 PH-1010）；双签分权（调配/核对
 * 同人 PH-1011，Spec :226）为法定留痕硬守卫；退药追溯码逐码核验防回流（PH-1012，Spec §10）。
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

    /** 主数据读侧缓存（occupancy 读侧患者归一唯一消费方），非空 */
    private final PharmacyMasterDataCache masterDataCache;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import；Task 10 起扩十参——masterDataCache 承载
     * 占用查询读侧患者归一）。
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
     * @param masterDataCache        主数据读侧缓存（merged/split 订阅写、occupancy 读），非空
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
            ObjectMapper objectMapper,
            PharmacyMasterDataCache masterDataCache) {
        this.dispenseMapper = dispenseMapper;
        this.dispenseItemMapper = dispenseItemMapper;
        this.drugBatchMapper = drugBatchMapper;
        this.stockLedgerMapper = stockLedgerMapper;
        this.prescriptionMapper = prescriptionMapper;
        this.prescriptionItemMapper = prescriptionItemMapper;
        this.batchSelectService = batchSelectService;
        this.events = events;
        this.objectMapper = objectMapper;
        this.masterDataCache = masterDataCache;
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
        // 数据库写操作：调配留痕回填（内存态与 DB 迁移同步：上方 CAS 已置 PICKING，实体补写同值后再
        //   update——禁携带 CAS 前旧状态落库把状态机覆写回 CREATED，PrescriptionServiceImpl.create 同款约定）
        d.setStatus("PICKING");
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
        // 数据库写操作：核对留痕回填（内存态与 DB 迁移同步：上方 CAS 已置 PICKED，实体补写同值后再
        //   update——禁携带 CAS 前旧状态落库把状态机覆写回 PICKING，pick 调配留痕同款约定）
        d.setStatus("PICKED");
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
    @Transactional
    public void acceptReturn(DispenseReturnRequest req) {
        Dispense d = requireByNo(req.dispenseNo());
        String operator = OperatorContextHolder.get();
        if ("DISPENSING_CANCEL".equals(req.mode())) {
            returnDuringDispensing(d, req, operator);
            return;
        }
        if (!"ISSUED_RETURN".equals(req.mode())) {
            throw new BizException(
                    PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED, HttpStatus.BAD_REQUEST, "未知退药受理模式：" + req.mode());
        }
        // 时点①（R2-13）：发药后实物退——ISSUED（或既往部分退后的 PART_RETURNED）可继续受理
        if (!"ISSUED".equals(d.getStatus()) && !"PART_RETURNED".equals(d.getStatus())) {
            throw new BizException(
                    PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "调剂单状态不允许实物退药：" + d.getStatus());
        }
        List<DispenseItem> items = dispenseItemMapper.selectList(Wrappers.<DispenseItem>lambdaQuery()
                .eq(DispenseItem::getDispenseId, d.getId())
                .eq(DispenseItem::getItemStatus, "NORMAL")
                .orderByAsc(DispenseItem::getId));
        boolean allReturned = true;
        // 退药行摘要（随 returned 事件携出——id 29 desc 冻结 lines[] 非空，billing/退费联动读此面）
        List<DispenseReturnedPayload.Line> summary = new ArrayList<>(items.size());
        for (DispenseItem item : items) {
            DispenseReturnRequest.ReturnLine line = req.items().stream()
                    .filter(l -> String.valueOf(item.getPrescriptionItemId()).equals(l.prescriptionItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BizException(
                            PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                            HttpStatus.BAD_REQUEST,
                            "退药受理缺行：prescriptionItemId=" + item.getPrescriptionItemId()));
            BigDecimal returnQty = parseReturnQuantity(line.returnQuantity());
            BigDecimal returnable = item.getIssuedQty().subtract(item.getReturnedQty());
            // 数量守卫：累计退药不得超实发（PH-1013）；追溯码逐码核验与发药采集一致（PH-1012 防回流药）
            if (returnQty.signum() <= 0 || returnQty.compareTo(returnable) > 0) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "退药数量超可退余额：itemCode=" + item.getItemCode());
            }
            List<String> issuedTraces = fromJson(item.getTraceCodes());
            if (line.traceCodes() == null || !issuedTraces.containsAll(line.traceCodes())) {
                throw new BizException(
                        PharmacyErrorCode.TRACE_CODE_MISMATCH,
                        HttpStatus.CONFLICT,
                        "追溯码与发药记录不一致（防回流药核验拒）：itemCode=" + item.getItemCode());
            }
            // 数据库写操作：批次回补 + 回补流水（红线 2：与批次变更同事务；回补同一批次保批号勾稽）
            if (drugBatchMapper.restock(item.getBatchId(), returnQty) != 1) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "批次回补失败：batchId=" + item.getBatchId());
            }
            StockLedger ledger = new StockLedger();
            ledger.setStorehouse(d.getStorehouse());
            ledger.setDrugId(item.getDrugId());
            ledger.setBatchId(item.getBatchId());
            ledger.setAction("RETURN_RESTOCK");
            ledger.setQuantity(returnQty);
            ledger.setRefDoc(d.getDispenseNo());
            ledger.setOperator(operator);
            stockLedgerMapper.insert(ledger);
            item.setReturnedQty(item.getReturnedQty().add(returnQty));
            dispenseItemMapper.updateById(item);
            // 数据库写操作：处方明细已退数量累计回写（V701 列注释「已退数量（退药回写）」承诺的回写点，
            //   occupancy returnedQuantity 数据源；服务端原子累加、与 dispense_item 回写同事务；
            //   0 行=明细行缺失/已逻辑删脏数据，显式拒整事务回滚——casMarkFeesSettled 影响行数范式）
            if (prescriptionItemMapper.accumulateReturnedQuantity(item.getPrescriptionItemId(), returnQty) != 1) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "处方明细已退数量回写失败：prescriptionItemId=" + item.getPrescriptionItemId());
            }
            summary.add(new DispenseReturnedPayload.Line(
                    item.getItemCode(), item.getBatchNo(), returnQty.toPlainString(), issuedTraces));
            if (item.getReturnedQty().compareTo(item.getIssuedQty()) != 0) {
                allReturned = false;
            }
        }
        // 数据库写操作：发药单终态（受理完成即置——Spec :134；处方终态归 refund.approved，Spec :132）
        String target = allReturned ? "FULL_RETURNED" : "PART_RETURNED";
        String from = "ISSUED".equals(d.getStatus()) ? "ISSUED" : "PART_RETURNED";
        if (dispenseMapper.casStatus(d.getId(), from, target) != 1) {
            throw new BizException(
                    PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "退药终态迁移并发被抢：" + d.getDispenseNo());
        }
        // 事务内发应用事件：退药受理完成（billing 占用回退与退费联动依据；lines 摘要与 id 29 desc 对齐）
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_DISPENSE_RETURNED,
                new DispenseReturnedPayload(
                        d.getDispenseNo(),
                        d.getRxNo(),
                        d.getRxNo(),
                        d.getPatientId(),
                        d.getVisitId(),
                        allReturned,
                        summary)));
        log.info(
                "退药受理完成：dispenseNo={}，mode={}，allReturned={}，lines={}，operator={}",
                d.getDispenseNo(),
                req.mode(),
                allReturned,
                summary.size(),
                operator);
    }

    /** 时点②（R2-13）：发药中明细退场——释放锁定批次（不落流水不发事件），处方保持 DISPENSING */
    private void returnDuringDispensing(Dispense d, DispenseReturnRequest req, String operator) {
        if (!"PICKING".equals(d.getStatus())) {
            throw new BizException(
                    PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅配药中的发药单可明细退场：" + d.getStatus());
        }
        List<DispenseItem> items = dispenseItemMapper.selectList(Wrappers.<DispenseItem>lambdaQuery()
                .eq(DispenseItem::getDispenseId, d.getId())
                .eq(DispenseItem::getItemStatus, "NORMAL")
                .orderByAsc(DispenseItem::getId));
        for (DispenseItem item : items) {
            DispenseReturnRequest.ReturnLine line = req.items().stream()
                    .filter(l -> String.valueOf(item.getPrescriptionItemId()).equals(l.prescriptionItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BizException(
                            PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                            HttpStatus.BAD_REQUEST,
                            "明细退场缺行：prescriptionItemId=" + item.getPrescriptionItemId()));
            BigDecimal qty = parseReturnQuantity(line.returnQuantity());
            // 数据库写操作：释放锁定（锁定数非数量流水，不落 stock_ledger；明细退场标记）
            if (drugBatchMapper.releaseLock(item.getBatchId(), qty) != 1) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "批次锁定释放失败：batchId=" + item.getBatchId());
            }
            item.setItemStatus("CANCELLED");
            dispenseItemMapper.updateById(item);
        }
        log.warn("发药中明细退场：dispenseNo={}，处方保持 DISPENSING 继续剩余明细；operator={}", d.getDispenseNo(), operator);
    }

    @Override
    @Transactional
    public void confirmRefundTerminal(long patientId) {
        // 数据库读操作：该患者受理已终态的发药单（终态在受理完成即置——镜像源）
        List<Dispense> returned = baseMapper.selectList(Wrappers.<Dispense>lambdaQuery()
                .eq(Dispense::getPatientId, patientId)
                .in(Dispense::getStatus, "PART_RETURNED", "FULL_RETURNED")
                .orderByAsc(Dispense::getId));
        for (Dispense d : returned) {
            Prescription rx = prescriptionMapper.selectOne(
                    Wrappers.<Prescription>lambdaQuery().eq(Prescription::getRxNo, d.getRxNo()));
            if (rx == null) {
                log.warn("退费终态确认无法定位处方：rxNo={}", d.getRxNo());
                continue;
            }
            if ("DISPENSED".equals(rx.getStatus())) {
                // 数据库写操作：处方终态镜像发药单受理终态（Spec :132：billing.refund.approved 后终态）
                String target = "FULL_RETURNED".equals(d.getStatus()) ? "FULL_RETURNED" : "PART_RETURNED";
                prescriptionMapper.casStatus(rx.getId(), "DISPENSED", target);
                log.info("退费终态收敛：rxNo={}→{}（以 M13 回执为退费权威）", d.getRxNo(), target);
            } else if ("PART_RETURNED".equals(rx.getStatus()) || "FULL_RETURNED".equals(rx.getStatus())) {
                log.info("退费终态确认幂等跳过（已终态）：rxNo={}，status={}", d.getRxNo(), rx.getStatus());
            } else {
                // 未发药退费（PENDING_DISPENSE/DISPENSING）：终态确认归 outpatient.order.cancelled，PR-5 回切
                log.warn("退费终态确认跳过（未发药处方终态确认随 PR-5 回切）：rxNo={}，status={}", d.getRxNo(), rx.getStatus());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<OccupancyVO> occupancy(long patientId, String visitId, String itemCode) {
        // 读侧归一：merged 从档入参经缓存映射主档（未命中原样返回；M-25 订阅闭环的查询侧兑现）
        long pid = masterDataCache.resolveSurvivor(patientId);
        // 数据库读操作：该患者处方清单（visitId 可选过滤；占用行集无命中即空集出网）
        List<Prescription> rxs = prescriptionMapper.selectList(Wrappers.<Prescription>lambdaQuery()
                .eq(Prescription::getPatientId, pid)
                .eq(visitId != null && !visitId.isBlank(), Prescription::getVisitId, visitId)
                .orderByAsc(Prescription::getId));
        if (rxs.isEmpty()) {
            // 空集短路（语义同原「占用行集无命中即空集出网」）：不发起明细/发药单 in 批查（空 id 集不出网）
            return List.of();
        }
        // 数据库读操作：本集处方明细一次 in 批装载（itemCode 可选过滤；prescription_id+id 升序保住
        //   每处方内明细相对序与原逐行单查等价，A.4.3-14 循环单查改批量拒绝 N+1）
        List<Long> rxIds = rxs.stream().map(Prescription::getId).toList();
        Map<Long, List<PrescriptionItem>> itemsByRx = prescriptionItemMapper
                .selectList(Wrappers.<PrescriptionItem>lambdaQuery()
                        .in(PrescriptionItem::getPrescriptionId, rxIds)
                        .eq(itemCode != null && !itemCode.isBlank(), PrescriptionItem::getItemCode, itemCode)
                        .orderByAsc(PrescriptionItem::getPrescriptionId)
                        .orderByAsc(PrescriptionItem::getId))
                .stream()
                .collect(Collectors.groupingBy(PrescriptionItem::getPrescriptionId));
        // 数据库读操作：本集发药单一次 in 批取（复刻原逐行 selectOne 全部谓词 rx_no=...：一处方一张
        //   活动单 uk_dispense_rx_active；id 升序后每 rxNo 首行即与单查唯一命中同一行，重复命中属脏
        //   数据取首行），按 rxNo 建映射供投影消费；未配药处方映射缺位→null（占用行仍出，退费前置）
        List<String> rxNos = rxs.stream().map(Prescription::getRxNo).toList();
        Map<String, Dispense> dispenseByRxNo = baseMapper
                .selectList(Wrappers.<Dispense>lambdaQuery()
                        .in(Dispense::getRxNo, rxNos)
                        .orderByAsc(Dispense::getId))
                .stream()
                .collect(Collectors.toMap(Dispense::getRxNo, Function.identity(), (first, duplicate) -> first));
        List<OccupancyVO> rows = new ArrayList<>();
        for (Prescription rx : rxs) {
            for (PrescriptionItem item : itemsByRx.getOrDefault(rx.getId(), List.of())) {
                rows.add(OccupancyVO.from(rx, item, dispenseByRxNo.get(rx.getRxNo())));
            }
        }
        return rows;
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
        // 数据库读操作：处方明细快照取行（应发数=处方数量，批次/追溯码随 pick 回填）
        List<PrescriptionItem> items = prescriptionItemMapper.selectList(Wrappers.<PrescriptionItem>lambdaQuery()
                .eq(PrescriptionItem::getPrescriptionId, rx.getId())
                .eq(PrescriptionItem::getStatus, "NORMAL")
                .orderByAsc(PrescriptionItem::getId));
        List<DispenseItem> rows = new ArrayList<>(items.size());
        for (PrescriptionItem item : items) {
            DispenseItem row = new DispenseItem();
            row.setDispenseId(dispense.getId());
            row.setPrescriptionItemId(item.getId());
            row.setDrugId(item.getDrugId());
            row.setItemCode(item.getItemCode());
            row.setRequestedQty(item.getQuantity());
            row.setItemStatus("NORMAL");
            rows.add(row);
        }
        // 数据库写操作：明细一次批插（A.4.3-16 saveBatch：JDBC 批处理 + ASSIGN_ID 自动填充 ID，
        //   调用方 releaseByVisit @Transactional 事务内承载）
        Db.saveBatch(rows);
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
     * 退药数量解析守卫（PH-1016，W-22⑦）：returnQuantity 为 DECIMAL string 承载，非数字串显式
     * 拒 400（禁 NumberFormatException 直穿 500 出契约外形态）；数值合法性（&gt;0、不超可退余额）
     * 仍归 PH-1013 服务层后续守卫，本守卫只管格式。
     *
     * @param returnQuantity 退药数量 DECIMAL string，非空（DTO @NotBlank 承载）
     * @return 已解析数量
     * @throws BizException PH-1016（400）：非数字串
     */
    private static BigDecimal parseReturnQuantity(String returnQuantity) {
        try {
            return new BigDecimal(returnQuantity);
        } catch (NumberFormatException e) {
            throw new BizException(
                    PharmacyErrorCode.NUMERIC_FIELD_MALFORMED, HttpStatus.BAD_REQUEST, "退药数量须为数字串：" + returnQuantity);
        }
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
