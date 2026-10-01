package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.SettlementQueryPort;
import com.fuyun.common.constants.TimeConstants;
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
import com.fuyun.pharmacy.service.IBatchSelectService;
import com.fuyun.pharmacy.service.IDispenseService;
import com.fuyun.pharmacy.vo.DispenseVO;
import com.fuyun.pharmacy.vo.OccupancyVO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发药服务实现：放行链（charged 按 rxNos 精确清单→PENDING_DISPENSE+入队，裁决 4）、
 * 费用链（fee.created→PENDING_FEE）、调剂三段闭环（pick FEFO 批次锁定+追溯码采集 → verify
 * 双签核对+可选取药凭证核验 → issue 发药签名+批次扣减+出库流水+completed 事件）与退药受理
 * 两时点（ISSUED_RETURN 实物退批次回补+处方明细退药累计回写+returned 事件 / DISPENSING_CANCEL
 * 发药中明细退场释放锁定）、refund.approved 按单据清单终态收敛（注记⑦收口）、order.cancelled
 * 未发药作废路径（注记⑥回切）与执行占用查询（读侧经主数据缓存患者归一，供 M13 位）。
 * 状态机 CAS 收口：0 行=并发被抢/状态违例一律显式拒绝（无负库存，未锁先发即 PH-1010）；
 * 双签分权（调配/核对同人 PH-1011，Spec :226）为法定留痕硬守卫；退药追溯码逐码核验防回流
 * （PH-1012，Spec §10）；凭证核验=settlementNo 与处方归属一致性（PH-1018，裁决 8）。
 * 事件消费幂等双层：eventId 构件幂等之外，业务级「CAS 0 行→重读定性：已达目标态幂等跳过、
 * 其余状态 warn 跳过不上抛」（charged 重复投递仅放行一次，Spec §10 异常项）。uk_dispense_rx_active
 * 兜底防重复建单。装配归 PharmacyWebConfig @Import。
 * 查询形态（宪法 A.4.3-13）：主表（dispense）查询统一走 ServiceImpl 内置 lambdaQuery 链式；
 * 跨表/子表查询（prescription/prescription_item/dispense_item，另有 drug_batch 走条件更新
 * 通道、stock_ledger 走 Db 批插通道）不经本服务继承链，保留 Wrappers 手构——副表面无继承面可复用。
 * 写入形态（宪法 A.4.3-16，EX-37 批量写收拢）：循环内「无状态语义」的逐行 insert/updateById
 * 一律收集后批语句落库（stock_ledger 批插 Db.saveBatch、dispense_item 补丁批更
 * Db.updateBatchById）；「0 行防线」条件更新（casStatus/casIssue/lockQuantity/deductLocked/
 * restock/releaseLock/accumulateReturnedQuantity 与选批-锁定联动）逐行保留禁批量化——批量
 * 仅收敛写往返，禁吞并发/违例判定语义；明细批更走仅携 id+目标列的补丁实体（EX-24｜BE-A2-05
 * 并发防覆写，BUG-07 补丁回写同款形态）。
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

    /** 结算单反查端口（verify 凭证与处方归属一致性核验唯一消费方，billing api 只读面），非空 */
    private final SettlementQueryPort settlementQueryPort;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import；Task 11 起扩十一参——settlementQueryPort
     * 承载凭证核验反查；EX-37 收敛十参——流水批插改 Db 通道后 StockLedgerMapper 依赖卸除）。
     *
     * @param dispenseMapper         调剂单 mapper（ServiceImpl 继承 baseMapper 同源），非空
     * @param dispenseItemMapper     调剂明细 mapper，非空
     * @param drugBatchMapper        批次 mapper（锁定/扣减条件更新通道），非空
     * @param prescriptionMapper     处方 mapper，非空
     * @param prescriptionItemMapper 处方明细 mapper，非空
     * @param batchSelectService     FEFO 选批服务，非空
     * @param events                 应用事件发布器，非空
     * @param objectMapper           追溯码 JSON 读写器，非空
     * @param masterDataCache        主数据读侧缓存（merged/split 订阅写、occupancy 读），非空
     * @param settlementQueryPort    结算单反查端口（billing api 只读面），非空
     */
    public DispenseServiceImpl(
            DispenseMapper dispenseMapper,
            DispenseItemMapper dispenseItemMapper,
            DrugBatchMapper drugBatchMapper,
            PrescriptionMapper prescriptionMapper,
            PrescriptionItemMapper prescriptionItemMapper,
            IBatchSelectService batchSelectService,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper,
            PharmacyMasterDataCache masterDataCache,
            SettlementQueryPort settlementQueryPort) {
        this.dispenseMapper = dispenseMapper;
        this.dispenseItemMapper = dispenseItemMapper;
        this.drugBatchMapper = drugBatchMapper;
        this.prescriptionMapper = prescriptionMapper;
        this.prescriptionItemMapper = prescriptionItemMapper;
        this.batchSelectService = batchSelectService;
        this.events = events;
        this.objectMapper = objectMapper;
        this.masterDataCache = masterDataCache;
        this.settlementQueryPort = settlementQueryPort;
    }

    /**
     * 缴费放行（outpatient.order.charged 消费业务，Spec §5 流程 1；裁决 4 单据精确放行）：
     * 按结算覆盖的处方号精确清单逐 rxNo 收敛——处方号键集一次 IN 批查（A.4.3-14 批查化）→
     * 逐号三守卫分流（缺号脏差异 warn 跳过 / 非门诊急诊通道 warn 跳过 / CAS PENDING_FEE→
     * PENDING_DISPENSE 0 行重读定性：已达放行态幂等跳过、其余 warn 跳过不阻断消费位）→
     * 放行成功者建 CREATED 发药单与明细入队（uk_dispense_rx_active 兜底重投并发建单）。
     * 空清单=该结算无药品行（纯检查/检验结算），合法帧 info 跳过零查询。
     *
     * @param rxNos 本次结算覆盖的处方号精确清单，非 null（空清单=合法跳过面）；来源：
     *              outpatient.order.charged 载荷 rxNos（V204 id 25 冻结契约）
     */
    @Override
    @Transactional
    public void releaseByRxNos(List<String> rxNos) {
        // 空清单=该结算无药品行（纯检查/检验结算），合法帧 info 跳过（裁决 4：单据精确放行无对象）
        if (rxNos == null || rxNos.isEmpty()) {
            log.info("缴费放行跳过（该结算无药品行，纯检查/检验结算合法空清单）");
            return;
        }
        // 数据库读操作：charged 清单处方号键集一次 IN 批查（uk_rx_no 保证每号至多一行，与原逐号
        //   selectOne 同语义；脏差异缺号不出结果集→映射缺位即原 null 分支）——A.4.3-14 循环单查
        //   改批量，N 号 N 查收敛为 1 查，缩短 settlement.completed 消费事务持锁
        Map<String, Prescription> rxByNo =
                prescriptionMapper
                        .selectList(Wrappers.<Prescription>lambdaQuery().in(Prescription::getRxNo, rxNos))
                        .stream()
                        .collect(Collectors.toMap(
                                Prescription::getRxNo,
                                Function.identity(),
                                (first, duplicate) -> first,
                                LinkedHashMap::new));
        for (String rxNo : rxNos) {
            // 批查映射取行（原逐号 selectOne 同位替换；缺号映射缺位=null 即原脏差异分支，warn 留痕不阻断）
            Prescription rx = rxByNo.get(rxNo);
            if (rx == null) {
                log.warn("缴费放行跳过（清单处方号无法定位处方）：rxNo={}", rxNo);
                continue;
            }
            // 通道过滤：门诊/急诊处方为本通道放行对象（DISCHARGE 出院带药归 settlement.completed 分支）
            if (!"OUTPATIENT".equals(rx.getRxType()) && !"EMERGENCY".equals(rx.getRxType())) {
                log.warn("缴费放行跳过（非门诊/急诊通道处方）：rxNo={}，rxType={}", rxNo, rx.getRxType());
                continue;
            }
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

    /**
     * 费用回执（billing.fee.created 消费业务，Spec R2-14）：billingKey 第三段守卫——仅
     * trigger_point=PRESCRIPTION_EFFECTIVE（处方通道，sourceRef=rx_no）触达本方法，开单/
     * 日切等其他通道直接返回零查询；定位处方后 CAS 迁移 APPROVED→PENDING_FEE（0 行重读
     * 定性：已迁移幂等跳过、其余状态漂移 warn 跳过）。缺号（脏数据）warn 留痕不阻断通道。
     * 本方法自身无业务异常抛出面（消费侧口径：定性跳过不上抛）。
     *
     * @param billingKey 计费唯一键（patientId|sourceRef|triggerPoint|chargeItemId|billingDate
     *                   五段竖线分隔，sourceRef 取第二段），非空；来源：billing.fee.created
     *                   载荷（经 PharmacyBillingSyncListener 转发）
     */
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

    /**
     * 配药（调剂三段第一段，CREATED→PICKING / 处方 PENDING_DISPENSE→DISPENSING，Spec :132/:134）：
     * 双 CAS 进配药中 → 逐 NORMAL 明细行匹配采集面（缺行 PH-1006；追溯码空集违「无码不结」
     * PH-1006）→ FEFO 选批并条件锁定（选批 null 或锁定 0 行=并发超发/缺量 PH-1010 整事务
     * 回滚）→ 批次与追溯码 JSON 回填明细行 → 调配人留痕回填。适用场景：M06 药师工作站
     * 配药采集提交。任何一环拒绝整事务回滚（批次锁不残留）。
     *
     * @param dispenseNo 调剂单号（uk 唯一），非空；来源：工作站单据列表/扫码
     * @param lines      逐行采集面（prescriptionItemId 字符串化匹配 + 追溯码集），非空且须
     *                   覆盖单据全部 NORMAL 明细行；来源：M06 药师工作站逐盒采集提交
     * @throws BizException PH-1008（404 调剂单缺单）/ PH-1004（404 处方缺单）/
     *                      PH-1009（409 调剂单状态违例或 CAS 并发被抢）/ PH-1005（409 处方
     *                      状态违例）/ PH-1006（400 采集缺行或追溯码空集）/ PH-1010（409 批次
     *                      不足或锁定 0 行——建议处理：整事务已回滚，补货/换批后重提）
     */
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
        // 明细回填补丁行集（循环内仅收集，循环外一次批更——A.4.3-16 批量写纪律）
        List<DispenseItem> itemPatches = new ArrayList<>(items.size());
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
            // 选批 + 条件锁定（防并发超发硬防线；选批失败/锁定 0 行一律 PH-1010 整事务回滚）。
            //   EX-37 甄别：选批依赖前序锁定后的余量视图、锁定 0 行=并发超发防线——CAS 联动逐行
            //   保留禁批量化（OPT-03 批量 CAS 不适用于选批-锁定联动面）
            DrugBatch batch =
                    batchSelectService.selectForDispense(item.getDrugId(), d.getStorehouse(), item.getRequestedQty());
            if (batch == null || drugBatchMapper.lockQuantity(batch.getId(), item.getRequestedQty()) != 1) {
                throw new BizException(
                        PharmacyErrorCode.STOCK_INSUFFICIENT,
                        HttpStatus.CONFLICT,
                        "批次可用量不足，配药被拒：drugId=" + item.getDrugId());
            }
            // 数据库写操作（收集段）：批次与逐码采集回填明细行（追溯码 JSON 文本承载）——
            //   EX-24 补丁化：仅携 id+批次三列的补丁实体（NOT_NULL 更新策略下读点快照列——
            //   应发数/退药数/明细状态等不进 SET），读改写窗口内对端写面（退药累计回写等）
            //   提交不被整行快照覆写吞掉（TriageServiceImpl BUG-07 补丁回写同款形态）
            DispenseItem patch = new DispenseItem();
            patch.setId(item.getId());
            patch.setBatchId(batch.getId());
            patch.setBatchNo(batch.getBatchNo());
            patch.setTraceCodes(toJson(line.traceCodes()));
            itemPatches.add(patch);
        }
        // 数据库写操作：明细回填一次批更（A.4.3-16 Db.updateBatchById JDBC 批处理：N 行 N 更收敛
        //   1 批）——本段为无状态列回填无 0 行语义，可批；循环内任一拒绝整事务回滚（批次锁不
        //   残留），批更后置与逐行写面零对外差异
        if (!itemPatches.isEmpty()) {
            Db.updateBatchById(itemPatches);
        }
        // 数据库写操作：调配留痕走指定列补丁回填（EX-24｜BE-A2-05 并发防覆写）——仅携 id+状态同值
        //   +调配人的补丁实体落库，NOT_NULL 更新策略下读点快照列（单号/处方号/患者/库房/核对发药
        //   留痕）不进 SET 子句：读改写窗口内对端写面提交不被覆写吞掉（TriageServiceImpl BUG-07
        //   补丁回写同款形态）；内存实体同步保留（状态补写同值维持「禁携带 CAS 前旧状态落库」纪律，
        //   PR-4 IT 实证缺陷回归守卫语义不变，后续日志/出参直取内存态）
        d.setStatus("PICKING");
        d.setPicker(operator);
        Dispense patch = new Dispense();
        patch.setId(d.getId());
        patch.setStatus("PICKING");
        patch.setPicker(operator);
        dispenseMapper.updateById(patch);
        log.info("配药锁定完成：dispenseNo={}，picker={}，行数={}", dispenseNo, operator, items.size());
    }

    /**
     * 扫码核对（调剂三段第二段，PICKING→PICKED）：可选凭证核验前置（PR-5 裁决 8——凭证=
     * settlementNo，非空时经 billing SettlementQueryPort 反查该结算单下须有本处方 SETTLED
     * 费用行，不一致 PH-1018；空凭证跳过核验，追溯码逐码采集维持防回流主道）→ 双签分权
     * 硬守卫（核对人=当前登录者 ≠ 调配人，同人 PH-1011，Spec :226 法定留痕）→ CAS
     * PICKING→PICKED（0 行 PH-1009 并发被抢）→ 核对人留痕回填。适用场景：M06 药师工作
     * 站扫码核对提交。
     *
     * @param dispenseNo 调剂单号（uk 唯一），非空；来源：工作站单据列表/扫码
     * @param credential 取药凭证（结算单号 settlementNo），可空/空白（跳过核验面）；来源：
     *                   M06 药师工作站扫码/手工录入（患者缴费小票）
     * @throws BizException PH-1008（404 调剂单缺单）/ PH-1018（409 凭证与处方归属不一致——
     *                      建议处理：核对小票与处方归属后重录凭证）/ PH-1011（409 调配核对
     *                      同人双签——分权守卫硬拒，须换人核对）/ PH-1009（409 状态违例或
     *                      CAS 并发被抢）
     */
    @Override
    @Transactional
    public void verify(String dispenseNo, String credential) {
        Dispense d = requireByNo(dispenseNo);
        // 取药凭证核验（PR-5 裁决 8）：凭证=settlementNo，与处方归属一致性经 billing 反查——
        // 该结算单下无该处方 SETTLED 费用行即 PH-1018 拒（409）；空凭证跳过核验（追溯码逐码
        // 采集维持防回流主道——PR-4 注记⑧豁免面就此闭合）
        if (credential != null && !credential.isBlank() && !settlementQueryPort.settledUnder(credential, d.getRxNo())) {
            throw new BizException(
                    PharmacyErrorCode.CREDENTIAL_MISMATCH, HttpStatus.CONFLICT, "取药凭证与处方归属不一致：" + dispenseNo);
        }
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
        // 数据库写操作：核对留痕走指定列补丁回填（EX-24｜BE-A2-05 并发防覆写）——仅携 id+状态同值
        //   +核对人的补丁实体落库，NOT_NULL 更新策略下读点快照列（调配留痕 picker、单号/处方号等）
        //   不进 SET 子句：读改写窗口内对端写面提交不被覆写吞掉，「verify 不改写调配留痕」由列不
        //   携带承载（TriageServiceImpl BUG-07 补丁回写同款形态）；内存实体同步保留（状态补写同值
        //   维持「禁携带 CAS 前旧状态落库」纪律，pick 调配留痕同款约定，日志/出参直取内存态）
        d.setStatus("PICKED");
        d.setVerifier(operator);
        Dispense patch = new Dispense();
        patch.setId(d.getId());
        patch.setStatus("PICKED");
        patch.setVerifier(operator);
        dispenseMapper.updateById(patch);
        log.info(
                "扫码核对通过：dispenseNo={}，verifier={}，credential={}",
                dispenseNo,
                operator,
                credential == null ? "无" : "已核验");
    }

    /**
     * 发药签名（调剂三段第三段，PICKED→ISSUED / 处方 DISPENSING→DISPENSED 终态基点）：
     * 前置守卫（非 PICKED PH-1009；核对留痕缺失或同人双签重申定性 PH-1009）→ 发药签名
     * CAS（发药人/时刻一并落行）→ 逐明细批次锁定转扣减（0 行=未锁先发违例 PH-1010 整
     * 事务回滚）+ 出库流水同事务落账（红线 2：负数量 ISSUE 行与扣减勾稽）→ 处方 CAS 转
     * DISPENSED → 事务内发布 dispense.completed（批次摘要携追溯码，M03/billing 消费面，
     * AFTER_COMMIT 出线）。适用场景：M06 药师工作站发药确认。
     *
     * @param dispenseNo 调剂单号（uk 唯一），非空；来源：工作站单据列表/扫码
     * @throws BizException PH-1008（404 调剂单缺单）/ PH-1004（404 处方缺单）/ PH-1009
     *                      （409 状态违例/双签守卫失败/发药签名并发被抢）/ PH-1010（409 批次
     *                      锁定扣减失败——建议处理：整事务已回滚，按库存对账排查）/ PH-1005
     *                      （409 处方状态不允许发药收敛）
     */
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
        // 出库流水行集与实发回写补丁行集（循环内仅收集，循环外各一次批量写——A.4.3-16 批量写纪律）
        List<StockLedger> ledgers = new ArrayList<>(items.size());
        List<DispenseItem> itemPatches = new ArrayList<>(items.size());
        for (DispenseItem item : items) {
            // 数据库写操作：批次锁定转扣减（0 行=违例整事务回滚）。EX-37 甄别：扣减为「仅锁定余量
            //   可扣」的 0 行违例防线（未锁先发定性），CAS 条件更新逐行保留禁批量化
            if (drugBatchMapper.deductLocked(item.getBatchId(), item.getRequestedQty()) != 1) {
                throw new BizException(
                        PharmacyErrorCode.STOCK_INSUFFICIENT,
                        HttpStatus.CONFLICT,
                        "批次锁定扣减失败（未锁先发违例）：batchId=" + item.getBatchId());
            }
            // 数据库写操作（收集段）：出库流水行（红线 2：负数量 ISSUE 行与扣减同事务落账）
            StockLedger ledger = new StockLedger();
            ledger.setStorehouse(d.getStorehouse());
            ledger.setDrugId(item.getDrugId());
            ledger.setBatchId(item.getBatchId());
            ledger.setAction("ISSUE");
            ledger.setQuantity(item.getRequestedQty().negate());
            ledger.setRefDoc(dispenseNo);
            ledger.setOperator(operator);
            ledgers.add(ledger);
            // 数据库写操作（收集段）：实发数回写补丁——EX-24 补丁化仅携 id+实发数（读点快照列
            //   不进 SET，跨队乱序窗口内对端写面提交不被覆写吞掉，BUG-07 补丁回写同款形态）
            DispenseItem patch = new DispenseItem();
            patch.setId(item.getId());
            patch.setIssuedQty(item.getRequestedQty());
            itemPatches.add(patch);
            summary.add(new DispenseCompletedPayload.Line(
                    item.getItemCode(),
                    item.getBatchNo(),
                    item.getRequestedQty().toPlainString(),
                    fromJson(item.getTraceCodes())));
        }
        // 数据库写操作：出库流水一次批插 + 实发回写一次批更（A.4.3-16：N 行 2N 写收敛 2 批；
        //   行集与行序不变、ASSIGN_ID 自动填充、与扣减同事务红线 2 勾稽不变；任一扣减违例已在
        //   批写前抛出整事务回滚，与逐行写面零对外差异）
        if (!ledgers.isEmpty()) {
            Db.saveBatch(ledgers);
            Db.updateBatchById(itemPatches);
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

    /**
     * 退药受理（R2-13 两时点分流）：
     * <ul>
     * <li>mode=DISPENSING_CANCEL（时点②发药中明细退场）：仅 PICKING 单可受理——逐行释放
     * 锁定批次（锁定数非数量流水，不落 stock_ledger）+ 明细 CANCELLED 退场；处方保持
     * DISPENSING 继续剩余明细调配，不发 returned 事件。</li>
     * <li>mode=ISSUED_RETURN（时点①发药后实物退）：ISSUED/PART_RETURNED 态受理——逐明细
     * 缺行/数量守卫（≤0 或超可退余额 PH-1013）→ 追溯码逐码与发药采集核验（缺码/不一致
     * PH-1012 防回流药）→ 批次回补 + RETURN_RESTOCK 正数流水同事务（红线 2 勾稽）→
     * 处方明细已退数量原子累加回写 → 发药单 ISSUED/PART_RETURNED→PART/FULL_RETURNED
     * （受理完成即置终态，Spec :134）→ 发布 dispense.returned（lines 摘要非空，id 29
     * 冻结——billing 占用回退/退费联动读此面）。处方终态归 refund.approved 镜像
     * （Spec :132），本方法不触处方状态。</li>
     * </ul>
     * 适用场景：M06 药师工作站退药受理 / M05 病区退药发起。任一环拒绝整事务回滚零写面。
     *
     * @param req 退药受理入参（dispenseNo/mode/逐行 prescriptionItemId+returnQuantity+
     *            traceCodes），非空；来源：M06 工作站/M05 病区提交
     * @throws BizException PH-1008（404 调剂单缺单）/ PH-1016（400 退药数量非数字串）/
     *                      PH-1013（400 模式未知；409 状态违例/缺行/超可退余额/回补或释放
     *                      条件更新 0 行——并发竞争，重试或对账后重提）/ PH-1012（409 追溯码
     *                      不一致或缺码，防回流拒——建议处理：核对实物与发药记录）
     */
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
        // 回补流水行集与退药回写补丁行集（循环内仅收集，循环外各一次批量写——A.4.3-16 批量写纪律）
        List<StockLedger> ledgers = new ArrayList<>(items.size());
        List<DispenseItem> itemPatches = new ArrayList<>(items.size());
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
            // 数据库写操作：批次回补（回补同一批次保批号勾稽，红线 2：与批次变更同事务）。EX-37
            //   甄别：回补为「批次行在库」0 行漂移防线，CAS 条件更新逐行保留禁批量化
            if (drugBatchMapper.restock(item.getBatchId(), returnQty) != 1) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "批次回补失败：batchId=" + item.getBatchId());
            }
            // 数据库写操作（收集段）：回补流水行（正数量 RETURN_RESTOCK，红线 2 同事务勾稽）
            StockLedger ledger = new StockLedger();
            ledger.setStorehouse(d.getStorehouse());
            ledger.setDrugId(item.getDrugId());
            ledger.setBatchId(item.getBatchId());
            ledger.setAction("RETURN_RESTOCK");
            ledger.setQuantity(returnQty);
            ledger.setRefDoc(d.getDispenseNo());
            ledger.setOperator(operator);
            ledgers.add(ledger);
            // 数据库写操作（收集段）：退药数回写补丁——EX-24 补丁化仅携 id+退药数（读点快照列不
            //   进 SET，跨队乱序窗口内对端写面提交不被覆写吞掉，BUG-07 补丁回写同款形态）；
            //   内存实体同步累计保留（后续 allReturned 判定直取累计后内存态）
            BigDecimal returnedTotal = item.getReturnedQty().add(returnQty);
            item.setReturnedQty(returnedTotal);
            DispenseItem patch = new DispenseItem();
            patch.setId(item.getId());
            patch.setReturnedQty(returnedTotal);
            itemPatches.add(patch);
            // 数据库写操作：处方明细已退数量累计回写（V701 列注释「已退数量（退药回写）」承诺的回写点，
            //   occupancy returnedQuantity 数据源；服务端原子累加、与 dispense_item 回写同事务；
            //   0 行=明细行缺失/已逻辑删脏数据，显式拒整事务回滚——casMarkFeesSettled 影响行数范式）。
            //   EX-37 甄别：服务端原子累加（SET 列=自身+增量）语义禁批量化，逐行保留
            if (prescriptionItemMapper.accumulateReturnedQuantity(item.getPrescriptionItemId(), returnQty) != 1) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "处方明细已退数量回写失败：prescriptionItemId=" + item.getPrescriptionItemId());
            }
            summary.add(new DispenseReturnedPayload.Line(
                    item.getItemCode(), item.getBatchNo(), returnQty.toPlainString(), issuedTraces));
            if (returnedTotal.compareTo(item.getIssuedQty()) != 0) {
                allReturned = false;
            }
        }
        // 数据库写操作：回补流水一次批插 + 退药回写一次批更（A.4.3-16：N 行 2N 写收敛 2 批；行集
        //   与行序不变、与回补同事务红线 2 勾稽不变；任一回补/回写违例已在批写前抛出整事务回滚，
        //   与逐行写面零对外差异）
        if (!ledgers.isEmpty()) {
            Db.saveBatch(ledgers);
            Db.updateBatchById(itemPatches);
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
        // 明细退场补丁行集（循环内仅收集，循环外一次批更——A.4.3-16 批量写纪律）
        List<DispenseItem> itemPatches = new ArrayList<>(items.size());
        for (DispenseItem item : items) {
            DispenseReturnRequest.ReturnLine line = req.items().stream()
                    .filter(l -> String.valueOf(item.getPrescriptionItemId()).equals(l.prescriptionItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BizException(
                            PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                            HttpStatus.BAD_REQUEST,
                            "明细退场缺行：prescriptionItemId=" + item.getPrescriptionItemId()));
            BigDecimal qty = parseReturnQuantity(line.returnQuantity());
            // 数据库写操作：释放锁定（锁定数非数量流水，不落 stock_ledger）。EX-37 甄别：释放为
            //   「锁定数足额」0 行漂移防线，CAS 条件更新逐行保留禁批量化
            if (drugBatchMapper.releaseLock(item.getBatchId(), qty) != 1) {
                throw new BizException(
                        PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "批次锁定释放失败：batchId=" + item.getBatchId());
            }
            // 数据库写操作（收集段）：明细退场补丁——EX-24 补丁化仅携 id+明细状态（读点快照列
            //   不进 SET，跨队乱序窗口内对端写面提交不被覆写吞掉，BUG-07 补丁回写同款形态）
            DispenseItem patch = new DispenseItem();
            patch.setId(item.getId());
            patch.setItemStatus("CANCELLED");
            itemPatches.add(patch);
        }
        // 数据库写操作：明细退场一次批更（A.4.3-16 Db.updateBatchById JDBC 批处理：N 行 N 更收敛
        //   1 批）——本段为无状态标记无 0 行语义，可批；任一释放违例整事务回滚零对外差异
        if (!itemPatches.isEmpty()) {
            Db.updateBatchById(itemPatches);
        }
        log.warn("发药中明细退场：dispenseNo={}，处方保持 DISPENSING 继续剩余明细；operator={}", d.getDispenseNo(), operator);
    }

    /**
     * 退费终态收敛（billing.refund.approved 消费业务，PR-5 单据化收口——注记⑦误伤面闭合）：
     * 按退费涉及处方号清单两级键集批查（处方 IN 批查 + DISPENSED 键集活动发药单 IN 批查，
     * 2N 查收敛为恒 2 查）后逐 rxNo 分流——DISPENSED 态处方按发药单受理终态镜像
     * PART/FULL_RETURNED（Spec :132「billing.refund.approved 后终态」，M13 回执为退费权威）；
     * 已终态幂等跳过；缺号/无活动单/发药单受理未终态（跨队列乱序）warn 跳过待重投收敛；
     * 未发药退费 warn 跳过（终态确认归 order.cancelled 作废通道，注记⑥）。本方法自身无
     * 业务异常抛出面（消费侧口径：定性跳过不上抛），CAS 0 行静默交由重投收敛。
     *
     * @param rxNos 退费涉及的处方号精确清单，非 null 且按调用方契约非空（空清单零查询零
     *              日志兜底直返）；来源：billing SettlementQueryPort.sourceRefsOfSettlement
     *              反查 rxRefs（PharmacyRefundApprovedListener 承载）
     */
    @Override
    @Transactional
    public void confirmRefundTerminalByRx(List<String> rxNos) {
        // 空清单零查询零日志（listener 侧已拦空数组，此处兜底与原空循环零查询语义对齐）
        if (rxNos.isEmpty()) {
            return;
        }
        // 数据库读操作：refund.approved 清单处方号键集一次 IN 批查（uk_rx_no 保证每号至多一行，与原逐号
        //   selectOne 同语义；脏差异缺号不出结果集→映射缺位即原 null 分支）——A.4.3-14 循环单查改批量，
        //   N 号 N 查收敛为 1 查，缩短 refund.approved 消费事务持锁
        Map<String, Prescription> rxByNo =
                prescriptionMapper
                        .selectList(Wrappers.<Prescription>lambdaQuery().in(Prescription::getRxNo, rxNos))
                        .stream()
                        .collect(Collectors.toMap(
                                Prescription::getRxNo,
                                Function.identity(),
                                (first, duplicate) -> first,
                                LinkedHashMap::new));
        // 第二级键集=第一级命中且 DISPENSED 的 rxNo 集（原逐号活动发药单查询的精确谓词面——仅该状态
        //   分支触达二级查询；按清单序过滤保键序确定）
        List<String> dispensedRxNos = rxNos.stream()
                .filter(rxNo -> rxByNo.get(rxNo) != null
                        && "DISPENSED".equals(rxByNo.get(rxNo).getStatus()))
                .toList();
        // 数据库读操作：活动发药单按 DISPENSED 键集一次 IN 批查（排除 CANCELLED——uk_dispense_rx_active
        //   允许取消态历史行共存，每号至多一行活动单=原逐号 selectOne 语义；缺号不出结果集→映射缺位即
        //   原无活动单分支）；空键集短路零查询（空 id 集不出网）——2N 查收敛为恒 2 查；
        //   主表查询走 ServiceImpl 内置 lambdaQuery 链式（宪法 A.4.3-13），条件谓词与链式化前逐字等价
        Map<String, Dispense> activeByRxNo = dispensedRxNos.isEmpty()
                ? Map.of()
                : lambdaQuery()
                        .in(Dispense::getRxNo, dispensedRxNos)
                        .ne(Dispense::getStatus, "CANCELLED")
                        .list()
                        .stream()
                        .collect(Collectors.toMap(
                                Dispense::getRxNo,
                                Function.identity(),
                                (first, duplicate) -> first,
                                LinkedHashMap::new));
        for (String rxNo : rxNos) {
            // 批查映射取行（原逐号 selectOne 同位替换；缺号映射缺位=null 即原脏差异分支）
            Prescription rx = rxByNo.get(rxNo);
            if (rx == null) {
                log.warn("退费终态确认无法定位处方：rxNo={}", rxNo);
                continue;
            }
            if ("DISPENSED".equals(rx.getStatus())) {
                // 批查映射取行（原逐号活动发药单 selectOne 同位替换；缺位=null 即原无活动单分支）
                Dispense d = activeByRxNo.get(rxNo);
                if (d == null) {
                    log.warn("退费终态确认跳过（无活动发药单）：rxNo={}", rxNo);
                    continue;
                }
                // 镜像源=发药单受理终态（受理完成即置，Spec :134）；未终态=回执先于退药受理到达
                //   （跨队列乱序），warn 跳过待受理完成后的回执重投收敛
                if (!"PART_RETURNED".equals(d.getStatus()) && !"FULL_RETURNED".equals(d.getStatus())) {
                    log.warn("退费终态确认跳过（发药单受理未终态，跨队列乱序待重投收敛）：rxNo={}，dispenseStatus={}", rxNo, d.getStatus());
                    continue;
                }
                // 数据库写操作：处方终态镜像发药单受理终态（Spec :132：billing.refund.approved 后终态）
                String target = "FULL_RETURNED".equals(d.getStatus()) ? "FULL_RETURNED" : "PART_RETURNED";
                prescriptionMapper.casStatus(rx.getId(), "DISPENSED", target);
                log.info("退费终态收敛：rxNo={}→{}（以 M13 回执为退费权威，单据精确清单）", rxNo, target);
            } else if ("PART_RETURNED".equals(rx.getStatus()) || "FULL_RETURNED".equals(rx.getStatus())) {
                log.info("退费终态确认幂等跳过（已终态）：rxNo={}，status={}", rxNo, rx.getStatus());
            } else {
                // 未发药退费（PENDING_DISPENSE/DISPENSING）：终态确认归 order.cancelled 作废通道（注记⑥）
                log.warn("退费终态确认跳过（未发药处方终态确认归作废通道）：rxNo={}，status={}", rxNo, rx.getStatus());
            }
        }
    }

    /**
     * 未发药作废（outpatient.order.cancelled 消费业务，PR-5 注记⑥回切）：按退费逆向处方号
     * 清单三级键集批查（处方/活动发药单/NORMAL 明细）后逐 rxNo 分流——已发药终态
     * （DISPENSED/PART/FULL_RETURNED）info 幂等跳过（归 refund.approved 双通道）；已
     * CANCELLED 幂等直返；状态域外（未缴费 APPROVED/PENDING_FEE 等）warn 跳过（归 cancel
     * API 通道）；PENDING_DISPENSE/DISPENSING 态处方 CAS 作废先行（0 行重读定性：终态
     * 幂等跳过/漂移 warn 跳过；禁半程写面）→ 活动发药单同步退场（明细批次锁释放、明细
     * CANCELLED、单据 CANCELLED，复用 DISPENSING_CANCEL 退场段形态）。脏数据显式暴露：
     * 未发药处方挂 ISSUED 活动单抛 PH-1009 拒整单静默作废。
     *
     * @param rxNos  退费逆向涉及的处方号精确清单，非 null 且按调用方契约非空（空清单零查询
     *               兜底直返）；来源：outpatient.order.cancelled 载荷 rxNos（V204 id 31
     *               冻结契约）经 billing 反查扇出
     * @param reason 退费原因（载荷透传，作废留痕日志锚点），可空
     * @throws BizException PH-1009（409 未发药处方遇已发药活动单——脏数据禁静默，建议处理：
     *                      人工对账处方与发药单状态）/ PH-1013（409 批次锁定释放 0 行——
     *                      批次漂移整事务回滚，重投再定性）
     */
    @Override
    @Transactional
    public void voidUndispensedByRx(List<String> rxNos, String reason) {
        if (rxNos.isEmpty()) {
            // 空清单零查询零写面（listener 侧已拦空数组，此处兜底与原空循环零查询语义对齐）
            return;
        }
        // 数据库读操作：退费逆向清单处方号键集一次 IN 批查（uk_rx_no 保证每号至多一行，与原逐号
        //   selectOne 同语义；脏差异缺号不出结果集→映射缺位即原 null 分支）——A.4.3-14 循环单查
        //   改批量，N 号 N 查收敛为 1 查
        Map<String, Prescription> rxByNo =
                prescriptionMapper
                        .selectList(Wrappers.<Prescription>lambdaQuery().in(Prescription::getRxNo, rxNos))
                        .stream()
                        .collect(Collectors.toMap(
                                Prescription::getRxNo,
                                Function.identity(),
                                (first, duplicate) -> first,
                                LinkedHashMap::new));
        // 数据库读操作：活动发药单按「全部命中处方 rxNo 集」IN 批查（排除 CANCELLED——
        //   uk_dispense_rx_active 允许取消态历史行共存；id 升序保每处方内单据序与原逐处方单查
        //   一致）按 rxNo 分组遇序保序。键集含守卫将跳过行：守卫在批查后按原循环序逐行判定，
        //   跳过行不消费自身分组（只扩大读面不改写面）；空集短路零查询（空 id 集不出网）；
        //   主表查询走 ServiceImpl 内置 lambdaQuery 链式（宪法 A.4.3-13），条件/序键与链式化前逐字等价
        Map<String, List<Dispense>> activesByRxNo = rxByNo.isEmpty()
                ? Map.of()
                : lambdaQuery()
                        .in(Dispense::getRxNo, rxByNo.keySet())
                        .ne(Dispense::getStatus, "CANCELLED")
                        .orderByAsc(Dispense::getId)
                        .list()
                        .stream()
                        .collect(Collectors.groupingBy(Dispense::getRxNo, LinkedHashMap::new, Collectors.toList()));
        // 数据库读操作：NORMAL 明细行按「全部涉及发药单 id 集」IN 批查（退场行/取消行不重复处置；
        //   组内 id 升序与原逐单单查一致）按发药单 id 分组遇序保序；含守卫将跳过/ISSUED 将拒单的
        //   单据同理只扩大读面不触写面；空集短路零查询
        List<Long> dispenseIds = activesByRxNo.values().stream()
                .flatMap(List::stream)
                .map(Dispense::getId)
                .toList();
        Map<Long, List<DispenseItem>> itemsByDispenseId = dispenseIds.isEmpty()
                ? Map.of()
                : dispenseItemMapper
                        .selectList(Wrappers.<DispenseItem>lambdaQuery()
                                .in(DispenseItem::getDispenseId, dispenseIds)
                                .eq(DispenseItem::getItemStatus, "NORMAL")
                                .orderByAsc(DispenseItem::getDispenseId)
                                .orderByAsc(DispenseItem::getId))
                        .stream()
                        .collect(Collectors.groupingBy(
                                DispenseItem::getDispenseId, LinkedHashMap::new, Collectors.toList()));
        for (String rxNo : rxNos) {
            // 批查映射取行（原逐号 selectOne 同位替换；缺号映射缺位=null 即原脏差异分支）
            Prescription rx = rxByNo.get(rxNo);
            if (rx == null) {
                log.warn("退费逆向作废跳过（清单处方号无法定位处方）：rxNo={}", rxNo);
                continue;
            }
            // 已发药终态：refund.approved 双通道已收敛（或应收敛），作废通道禁触碰
            if ("DISPENSED".equals(rx.getStatus())
                    || "PART_RETURNED".equals(rx.getStatus())
                    || "FULL_RETURNED".equals(rx.getStatus())) {
                log.info("退费逆向作废幂等跳过（已发药终态归 refund.approved 双通道）：rxNo={}，status={}", rxNo, rx.getStatus());
                continue;
            }
            // 已作废：重投幂等直返
            if ("CANCELLED".equals(rx.getStatus())) {
                log.info("退费逆向作废幂等跳过（已作废）：rxNo={}", rxNo);
                continue;
            }
            // 状态域外守卫：未缴费作废（APPROVED/PENDING_FEE 等）归 cancel API 通道，本通道不承接
            if (!"PENDING_DISPENSE".equals(rx.getStatus()) && !"DISPENSING".equals(rx.getStatus())) {
                log.warn("退费逆向作废跳过（状态不在未发药作废域）：rxNo={}，status={}", rxNo, rx.getStatus());
                continue;
            }
            // 数据库写操作：处方 CAS 作废先行（0 行=与发药签名等并发被抢/终态漂移——重读定性，
            //   禁半程写面：发药单退场与锁释放后置于 CAS 成功）；重读为条件分支单查保留——批查
            //   快照过期的收敛锚（ChargingServiceImpl:136/:153 同性质豁免先例，不批查化）
            if (prescriptionMapper.casStatus(rx.getId(), rx.getStatus(), "CANCELLED") != 1) {
                Prescription latest = prescriptionMapper.selectById(rx.getId());
                String latestStatus = latest == null ? "UNKNOWN" : latest.getStatus();
                if ("CANCELLED".equals(latestStatus)
                        || "DISPENSED".equals(latestStatus)
                        || "PART_RETURNED".equals(latestStatus)
                        || "FULL_RETURNED".equals(latestStatus)) {
                    log.info("退费逆向作废幂等跳过（重读已达终态）：rxNo={}，status={}", rxNo, latestStatus);
                } else {
                    log.warn("退费逆向作废跳过（状态漂移）：rxNo={}，status={}", rxNo, latestStatus);
                }
                continue;
            }
            // 批查分组取单（原逐处方活动发药单 selectList 同位替换；缺位空清单=原空返回形态）
            List<Dispense> actives = activesByRxNo.getOrDefault(rxNo, List.of());
            for (Dispense d : actives) {
                // 脏数据显式暴露：未发药处方挂已发药活动单（状态机不可能态，禁静默作废已发药单据）
                if ("ISSUED".equals(d.getStatus())) {
                    throw new BizException(
                            PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED,
                            HttpStatus.CONFLICT,
                            "未发药作废遇已发药活动单（脏数据显式暴露禁静默）：rxNo=" + rxNo + "，dispenseNo=" + d.getDispenseNo());
                }
                // 批查分组取行（原逐单 NORMAL 明细 selectList 同位替换；缺位空清单=原空返回形态）
                List<DispenseItem> items = itemsByDispenseId.getOrDefault(d.getId(), List.of());
                // 明细退场补丁行集（单内收集、单内一次批更——保持「明细先行→单据 CAS 后置」原写序）
                List<DispenseItem> itemPatches = new ArrayList<>(items.size());
                for (DispenseItem item : items) {
                    // 数据库写操作：释放锁定批次（锁定数非数量流水，不落 stock_ledger——DISPENSING_CANCEL
                    //   退场段同款形态；CREATED 单明细未锁批 batchId 缺位零释放面）；0 行=批次漂移整事务
                    //   回滚。EX-37 甄别：释放为「锁定数足额」0 行漂移防线，CAS 条件更新逐行保留禁批量化
                    if (item.getBatchId() != null
                            && drugBatchMapper.releaseLock(item.getBatchId(), item.getRequestedQty()) != 1) {
                        throw new BizException(
                                PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED,
                                HttpStatus.CONFLICT,
                                "批次锁定释放失败：batchId=" + item.getBatchId());
                    }
                    // 数据库写操作（收集段）：明细退场补丁——EX-24 补丁化仅携 id+明细状态（读点快照
                    //   列不进 SET，跨队乱序窗口内对端写面提交不被覆写吞掉，BUG-07 补丁回写同款形态）
                    DispenseItem patch = new DispenseItem();
                    patch.setId(item.getId());
                    patch.setItemStatus("CANCELLED");
                    itemPatches.add(patch);
                }
                // 数据库写操作：单内明细退场一次批更（A.4.3-16：M 行 M 更收敛 1 批/单，置于单据 CAS
                //   前保持原「明细→单据」写序与锁序；本段为无状态标记无 0 行语义可批；空集零批调用
                //   =原零写面）
                if (!itemPatches.isEmpty()) {
                    Db.updateBatchById(itemPatches);
                }
                // 数据库写操作：发药单同步作废（Spec :134 CREATED/PICKING→CANCELLED 处方作废联动；
                //   PICKED 已核待发同样未出库，随整单退场释放锁）；0 行=并发被抢显式拒（事务回滚重投再定性）
                if (dispenseMapper.casStatus(d.getId(), d.getStatus(), "CANCELLED") != 1) {
                    throw new BizException(
                            PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED,
                            HttpStatus.CONFLICT,
                            "发药单作废并发被抢：dispenseNo=" + d.getDispenseNo());
                }
            }
            log.info("退费逆向未发药处方作废：rxNo={}，{}→CANCELLED，reason={}", rxNo, rx.getStatus(), reason);
        }
    }

    /**
     * 执行占用查询（只读事务，供 M13 退费前置校验调用位，Spec :172；billing 不切——
     * BILL-1017 维持 exec_occupancy_status 列口径，本 API 为 P3 切换面）：读侧患者归一
     * （merged 从档经主数据缓存映射主档，未命中原样返回）→ 处方清单批查（visitId 可选
     * 过滤）→ 明细一次 IN 批装载（itemCode 可选过滤，拒绝 N+1）→ 活动发药单一次 IN 批
     * 取（排除 CANCELLED，一处方一张活动单）→ 处方×明细×发药单三维投影出网。未配药
     * 处方发药单维度 null（占用行仍出，退费前置语义）。空集短路零明细/发药单批查。
     *
     * @param patientId 患者 id（merged 从档入参经缓存归一主档），非空；来源：M13 退费
     *                  校验按结算患者定位
     * @param visitId   就诊号（O 型 14 位），可空（空=不过滤全就诊）；来源：结算单归属就诊
     * @param itemCode  收费项目 code，可空/空白（空=不过滤全部项目）；来源：结算单费用行
     * @return 占用行集（每行=处方明细投影携发药单状态），非空；无命中返回空集
     */
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
        // 数据库读操作：本集发药单一次 in 批取，谓词排除 CANCELLED 与 uk_dispense_rx_active 配套互锁
        //   （V703 部分唯一索引仅约束活动行唯一，status<>CANCELLED 排除「取消单+重建活动单」共存的
        //   取消态历史行——一处方一张活动单，每 rxNo 至多 1 行命中，不重蹈原逐行 selectOne 多行抛错），
        //   按 rxNo 建映射供投影消费；未配药处方映射缺位→null（占用行仍出，退费前置）；
        //   主表查询走 ServiceImpl 内置 lambdaQuery 链式（宪法 A.4.3-13），条件/序键与链式化前逐字等价
        List<String> rxNos = rxs.stream().map(Prescription::getRxNo).toList();
        Map<String, Dispense> dispenseByRxNo = lambdaQuery()
                .in(Dispense::getRxNo, rxNos)
                .ne(Dispense::getStatus, "CANCELLED")
                .orderByAsc(Dispense::getId)
                .list()
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

    /**
     * 按处方号查活动发药单（只读事务，前端工作台回显）：uk_dispense_rx_active 一处方一张
     * 活动单语义定位，排除 CANCELLED 取消态历史行（W-24——order.cancelled 作废单与重建
     * 活动单允许共存，selectOne 禁多行歧义）→ 全状态明细随行装载（itemStatus 直出供
     * 工作台辨识退场行）→ VO 组装。无活动单返回 null（调用方组空集），本方法自身无业务
     * 异常抛出面。
     *
     * @param rxNo 处方号（uk_rx_no 唯一），非空；来源：工作站按处方号检索
     * @return 发药单出参（单据头+全状态明细）；无活动单返回 null
     */
    @Override
    @Transactional(readOnly = true)
    public DispenseVO getByRxNo(String rxNo) {
        // 数据库读操作：一处方一张活动单（uk_dispense_rx_active），按 rx_no 定位且排除 CANCELLED
        //   取消态历史行（W-24——order.cancelled 作废单与重建活动单允许共存，selectOne 禁多行歧义）；
        //   无活动单返回 null（调用方组空集）；主表查询走 ServiceImpl 内置 lambdaQuery 链式
        //   （宪法 A.4.3-13），条件谓词与链式化前逐字等价
        Dispense d = lambdaQuery()
                .eq(Dispense::getRxNo, rxNo)
                .ne(Dispense::getStatus, "CANCELLED")
                .one();
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
        // 技术日切取北京钟面（时区纪律专项 B 类）：摆药单号日期段不随容器时区漂移
        dispense.setDispenseNo("D" + LocalDate.now(TimeConstants.HEALTHCARE_TZ).format(RX_DATE)
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
        //   调用方 releaseByRxNos @Transactional 事务内承载）
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
        // 主表查询走 ServiceImpl 内置 lambdaQuery 链式（宪法 A.4.3-13），条件谓词与链式化前逐字等价
        Dispense d = lambdaQuery().eq(Dispense::getDispenseNo, dispenseNo).one();
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
            // EX-19 C 类留痕：DB 脏数据防御（非用户输入路径），保留 ISE 显式暴露禁静默吞码，不在 A/B 收口范围
            throw new IllegalStateException("调剂明细追溯码数据损坏（非 JSON 数组）：traceCodes=" + json, e);
        }
    }
}
