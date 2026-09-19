package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.PrescriptionItem;
import com.fuyun.pharmacy.mapper.DispenseItemMapper;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import com.fuyun.pharmacy.mapper.PrescriptionItemMapper;
import com.fuyun.pharmacy.mapper.PrescriptionMapper;
import com.fuyun.pharmacy.mapper.StockLedgerMapper;
import com.fuyun.pharmacy.service.IDispenseService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发药服务实现：放行链（charged→PENDING_DISPENSE+入队）与费用链（fee.created→PENDING_FEE）。
 * 事件消费幂等双层：eventId 构件幂等之外，业务级「CAS 0 行→重读定性：已达目标态幂等跳过、
 * 其余状态 warn 跳过不上抛」（charged 重复投递仅放行一次，Spec §10 异常项；重投帧若上抛会
 * 经有界重试进死信制造噪音，状态收敛类消费以跳过为幂等达成）。uk_dispense_rx_active 兜底
 * 防重复建单。装配归 PharmacyWebConfig @Import。
 */
@Slf4j
public class DispenseServiceImpl extends ServiceImpl<DispenseMapper, Dispense> implements IDispenseService {

    /** rx_no 日期段格式（与处方侧同源） */
    private static final DateTimeFormatter RX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** P1 演示库房：单一门诊药房（三级库随 P3 药房管理扩展） */
    public static final String STOREHOUSE_OUTP = "OUTP_PHARM";

    private final DispenseItemMapper dispenseItemMapper;

    private final DrugBatchMapper drugBatchMapper;

    private final StockLedgerMapper stockLedgerMapper;

    private final PrescriptionMapper prescriptionMapper;

    private final PrescriptionItemMapper prescriptionItemMapper;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import；drugBatchMapper/stockLedgerMapper 为
     * Task 6 调剂三段依赖，本任务构造位先到、字段先注入后使用）。
     */
    public DispenseServiceImpl(
            DispenseMapper dispenseMapper,
            DispenseItemMapper dispenseItemMapper,
            DrugBatchMapper drugBatchMapper,
            StockLedgerMapper stockLedgerMapper,
            PrescriptionMapper prescriptionMapper,
            PrescriptionItemMapper prescriptionItemMapper) {
        this.dispenseItemMapper = dispenseItemMapper;
        this.drugBatchMapper = drugBatchMapper;
        this.stockLedgerMapper = stockLedgerMapper;
        this.prescriptionMapper = prescriptionMapper;
        this.prescriptionItemMapper = prescriptionItemMapper;
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
}
