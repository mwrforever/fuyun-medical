package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.billing.api.PrescriptionFeePort;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.VisitIdValidator;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.api.PrescriptionCancelledPayload;
import com.fuyun.pharmacy.api.PrescriptionCreatedPayload;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.dto.PrescriptionCreateRequest;
import com.fuyun.pharmacy.dto.RxItemRequest;
import com.fuyun.pharmacy.entity.Drug;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.PrescriptionItem;
import com.fuyun.pharmacy.internal.PharmacyDomainEvent;
import com.fuyun.pharmacy.mapper.DrugMapper;
import com.fuyun.pharmacy.mapper.PrescriptionItemMapper;
import com.fuyun.pharmacy.mapper.PrescriptionMapper;
import com.fuyun.pharmacy.service.IPrescriptionService;
import com.fuyun.pharmacy.vo.PrescriptionItemVO;
import com.fuyun.pharmacy.vo.PrescriptionVO;
import com.fuyun.system.api.PracticeCheckPort;
import com.fuyun.system.api.PracticeCheckResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处方服务实现（开方主链）：预检占位恒「通过级」（主控裁决 5——P3 审方引擎接入前不建
 * review_task/audit_rule，预检分级结果字段结构预留固定 PASS）；CREATED→APPROVED 同事务
 * （Spec :132），created/cancelled 事件事务内经 ApplicationEventPublisher 发布（AFTER_COMMIT
 * 出 fy.topic）；作废无条件联动 billing PrescriptionFeePort 作废 PENDING 费用行（主控裁决 7，
 * 幂等——堵读态与 CAS 间 TOCTOU 资金窗口），已缴费（PENDING_DISPENSE+）拒绝并引导退药/退费。
 * practice/check 执业授权校验已接线（PR-5 Task 9，裁决 9 纵深防御，Spec :226）：落库前处方权
 * （PRESCRIPTION）一次，明细聚合后按命中集追加抗菌药最高分级与麻精类（至多两次）——任一未过
 * PH-1017（403，工号脱敏出文案），与 M03 开方入口校验（OP-1017）构成纵深两层。
 */
@Slf4j
public class PrescriptionServiceImpl extends ServiceImpl<PrescriptionMapper, Prescription>
        implements IPrescriptionService {

    /** rx_no 日期段格式（R+yyyyMMdd+6 位纳秒尾数） */
    private static final DateTimeFormatter RX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 执业授权词表·处方权（practice_grant grant_type，system api 契约消费方逐字引用） */
    private static final String GRANT_PRESCRIPTION = "PRESCRIPTION";

    /** 执业授权词表·麻精药品（明细命中 narcoticClass≠NORMAL 时追加校验） */
    private static final String GRANT_NARCOTIC = "NARCOTIC";

    /**
     * 抗菌药分级→授权词表映射（drug.antibio_class 值→practice_grant grant_type；NONE/空=非抗菌药
     * 不参与命中集——AntibacterialClass 词表 UNRESTRICTED=非限制使用级）
     */
    private static final Map<String, String> ANTIBIO_GRANT_BY_CLASS = Map.of(
            "UNRESTRICTED", "ANTIBIO_NONRESTRICT", "RESTRICTED", "ANTIBIO_RESTRICT", "SPECIAL", "ANTIBIO_SPECIAL");

    /**
     * 抗菌药授权严重序（grant 域，与 ANTIBIO_GRANT_BY_CLASS 值域同源；SPECIAL &gt; RESTRICTED &gt;
     * NONRESTRICT——命中集只查最高分级对应授权。R1 修正：比较域必须与聚合值同域（grant 名），
     * 严禁回退为 class 名表——跨域 indexOf 恒 -1 致严重序失效（首遇分级当最高级的安全漏洞））
     */
    private static final List<String> ANTIBIO_GRANT_SEVERITY =
            List.of("ANTIBIO_NONRESTRICT", "ANTIBIO_RESTRICT", "ANTIBIO_SPECIAL");

    private final PrescriptionItemMapper prescriptionItemMapper;

    private final DrugMapper drugMapper;

    private final PrescriptionFeePort prescriptionFeePort;

    /** 执业授权校验端口（system api 契约），非空；纵深防御 ①处方权/②抗菌药分级/麻精权校验 */
    private final PracticeCheckPort practiceCheckPort;

    /** 应用事件发布器（created/cancelled 事务内发布，AFTER_COMMIT 出 fy.topic），非空 */
    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import）。
     *
     * @param prescriptionMapper     处方 mapper（ServiceImpl 继承 baseMapper 同源），非空
     * @param prescriptionItemMapper 明细 mapper，非空
     * @param drugMapper             药品 mapper（开方逐行取药），非空
     * @param prescriptionFeePort    billing 费用作废端口（未缴费作废联动），非空
     * @param practiceCheckPort      执业授权校验端口（system api），非空；处方权/抗菌/麻精纵深校验
     * @param events                 应用事件发布器，非空
     */
    public PrescriptionServiceImpl(
            PrescriptionMapper prescriptionMapper,
            PrescriptionItemMapper prescriptionItemMapper,
            DrugMapper drugMapper,
            PrescriptionFeePort prescriptionFeePort,
            PracticeCheckPort practiceCheckPort,
            ApplicationEventPublisher events) {
        this.prescriptionItemMapper = prescriptionItemMapper;
        this.drugMapper = drugMapper;
        this.prescriptionFeePort = prescriptionFeePort;
        this.practiceCheckPort = practiceCheckPort;
        this.events = events;
    }

    @Override
    @Transactional
    public PrescriptionVO create(PrescriptionCreateRequest req) {
        // ① 纵深防御第一段（落库前置，裁决 9/Spec :226）：处方权强校验——运行态 userId 直作
        //    employeeId（AuthTokenInterceptor 注入十进制字符串化 userId，Task 2 身份链口径），
        //    未过 PH-1017（403）且零写；M03 开单入口校验之外的第二道
        long employeeId = parseOperatorAsEmployeeId();
        checkGrant(employeeId, GRANT_PRESCRIPTION);
        validateVisitAndType(req);
        // 请求面数量守卫（读库前置）：数量口径属请求自证，无需药品信息，禁未校验先落库；
        //   解析结果随行带回（W-22⑦：非数字串显式拒 400），明细落库复用禁二次 parse
        List<BigDecimal> quantities = parseQuantities(req.items());
        // 数据库写操作：处方主行落库（状态 CREATED，随即同事务 APPROVED——Spec :132 预检通过级同步完成）
        Prescription rx = new Prescription();
        rx.setRxNo(nextRxNo());
        rx.setRxType(req.rxType());
        rx.setPatientId(req.patientId());
        rx.setVisitId(req.visitId());
        rx.setDoctor(OperatorContextHolder.get());
        rx.setDeptCode(req.deptCode());
        rx.setDiagnosisCodes(req.diagnosisCodes() == null ? null : String.join(",", req.diagnosisCodes()));
        rx.setRxSource("DOCTOR_STATION");
        rx.setReviewLevel("PASS"); // 预检占位恒通过级（P3 引擎接入前固定值，分级结果字段结构预留）
        rx.setStatus("CREATED");
        baseMapper.insert(rx);
        if (baseMapper.casApprove(rx.getId()) != 1) {
            // 同事务内 CREATED 被并发抢改属数据异常，显式暴露（禁静默带 CREATED 生效）
            throw new IllegalStateException("处方放行迁移失败（CREATED→APPROVED），rxNo=" + rx.getRxNo());
        }
        // 内存态与 DB 迁移同步：出参 VO status 口径取迁移后终态（APPROVED），防出参与库不一致
        rx.setStatus("APPROVED");
        // 明细行装配 + 计费行快照（item_code+数量+用法摘要，created 事件唯一携带源，M-4 裁决）；
        //   数量取读库前置段已解析结果（同位置对应），单次解析
        List<PrescriptionItem> items = new ArrayList<>(req.items().size());
        boolean skinRequired = Boolean.TRUE.equals(req.skinTestRequired());
        String topNarcotic = "NORMAL";
        // ② 纵深防御命中集聚合：抗菌药最高分级对应授权（null=纯非抗菌药不追加）+ 麻精命中标记
        String topAntibioGrant = null;
        boolean narcoticHit = false;
        for (int i = 0; i < req.items().size(); i++) {
            RxItemRequest itemReq = req.items().get(i);
            Drug drug = drugMapper.selectById(itemReq.drugId());
            validateLine(drug, itemReq);
            PrescriptionItem row = new PrescriptionItem();
            row.setPrescriptionId(rx.getId());
            row.setDrugId(drug.getId());
            row.setDrugCode(drug.getDrugCode());
            row.setItemCode(drug.getItemCode());
            row.setQuantity(quantities.get(i));
            row.setUnit(itemReq.unit() == null ? drug.getUnit() : itemReq.unit());
            row.setSingleDose(itemReq.singleDose());
            row.setRouteCode(itemReq.routeCode());
            row.setFrequency(itemReq.frequency());
            row.setDays(itemReq.days());
            row.setUsageNote(itemReq.usageNote());
            row.setUsageSummary(buildUsageSummary(itemReq));
            row.setSkinTestFlag(Boolean.TRUE.equals(drug.getSkinTestFlag()));
            items.add(row);
            // 皮试要求聚合 + 处方类别随最高毒麻级别派生（红处方口径，Spec :106）+ ② 命中集聚合
            skinRequired = skinRequired || Boolean.TRUE.equals(drug.getSkinTestFlag());
            topNarcotic = maxNarcotic(topNarcotic, drug.getNarcoticClass());
            topAntibioGrant = maxAntibioGrant(topAntibioGrant, drug.getAntibioClass());
            narcoticHit = narcoticHit || (drug.getNarcoticClass() != null && !"NORMAL".equals(drug.getNarcoticClass()));
        }
        // ② 纵深防御第二段（明细聚合后、明细落库前）：按处方命中集追加校验——抗菌药取最高分级
        //    对应 grant_type、含麻精类查 NARCOTIC，任一未过即 PH-1017（403）；三次 check 以命中集
        //    为准（纯普通药处方仅 ① 一次 PRESCRIPTION 校验）
        if (topAntibioGrant != null) {
            checkGrant(employeeId, topAntibioGrant);
        }
        if (narcoticHit) {
            checkGrant(employeeId, GRANT_NARCOTIC);
        }
        // 数据库写操作：明细一次批插（A.4.3-16 saveBatch：JDBC 批处理 + ASSIGN_ID 自动填充 ID，
        //   本方法 @Transactional 事务内承载；前置 validateLine 逐行逻辑不受批插影响）
        Db.saveBatch(items);
        rx.setSkinTestRequired(skinRequired);
        rx.setRxCategory(topNarcotic);
        baseMapper.updateById(rx);
        // 事务内发应用事件：处方生效即发布（携计费行，M-4 裁决）。可靠投递链路（宪法 B.3-3，D-2 裁决）：
        //   Spring Modulith 事件发布注册表在本事务内同步落 event_publication，与处方业务变更原子提交
        //   ——处方生效与事件可投递记录同生共死，丢失窗口已消除；事务提交后 PharmacyEventPublisher
        //   以 @TransactionalEventListener(AFTER_COMMIT) 直发 MQ，监听器失败或实例宕机遗留的未完成
        //   发布由 EventOpsJob 定时重投（卡住超 5 分钟，重启重发默认关闭），残余风险仅剩消费侧幂等
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_PRESCRIPTION_CREATED,
                new PrescriptionCreatedPayload(
                        rx.getRxNo(),
                        rx.getRxNo(),
                        rx.getVisitId(),
                        rx.getPatientId(),
                        items.stream()
                                .map(i -> new PrescriptionCreatedPayload.Line(
                                        i.getItemCode(), i.getQuantity().toPlainString(), i.getUsageSummary()))
                                .toList())));
        log.info(
                "处方开立生效：rxNo={}，visitId={}，patientId={}，行数={}，category={}，reviewLevel=PASS",
                rx.getRxNo(),
                rx.getVisitId(),
                rx.getPatientId(),
                items.size(),
                topNarcotic);
        return PrescriptionVO.from(rx, toItemVOs(items));
    }

    @Override
    @Transactional
    public void cancel(String rxNo, String reason) {
        // 数据库读操作：按业务号定位（uk 唯一）
        Prescription rx =
                baseMapper.selectOne(Wrappers.<Prescription>lambdaQuery().eq(Prescription::getRxNo, rxNo));
        if (rx == null) {
            throw new BizException(PharmacyErrorCode.PRESCRIPTION_NOT_FOUND, HttpStatus.NOT_FOUND, "处方不存在：" + rxNo);
        }
        String status = rx.getStatus();
        // 状态守卫：仅未缴费（APPROVED/PENDING_FEE）可作废；已缴费未发药引导退费链；其余状态机违例
        if ("PENDING_DISPENSE".equals(status) || "DISPENSING".equals(status)) {
            throw new BizException(
                    PharmacyErrorCode.RX_CANCEL_BLOCKED_AFTER_CHARGE,
                    HttpStatus.CONFLICT,
                    "处方已缴费不可作废，请到收费窗口退费或药房退药受理后收敛终态：" + rxNo);
        }
        if (!"APPROVED".equals(status) && !"PENDING_FEE".equals(status)) {
            throw new BizException(
                    PharmacyErrorCode.PRESCRIPTION_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "处方状态不允许作废，当前状态：" + status);
        }
        // 未缴费联动：PENDING 费用行组同事务作废（billing 引擎 cancel 语义复用，主控裁决 7）。
        // 无条件调用（勿按读态 APPROVED 跳过）：读态与下方 casCancel 之间存在 TOCTOU 窗口——
        // fee.created 消费（APPROVED→PENDING_FEE 的 CAS）可落在其间，按读态跳过会留下处方已
        // CANCELLED 而 billing 侧 PENDING 费用行仍可经收费窗口结算的资金漏洞。幂等依据：port
        // 仅作废 PENDING 行（尚无费用行时零行 no-op；已缴 SETTLED 行不触），重复调用安全。
        prescriptionFeePort.cancelPendingBySourceRef(rxNo, reason);
        // 数据库写操作：CAS 终态（并发作废/放行抢先时 0 行定性拒绝）
        if (baseMapper.casCancel(rx.getId(), reason) != 1) {
            throw new BizException(
                    PharmacyErrorCode.PRESCRIPTION_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "处方状态已并发变更，作废未生效：" + rxNo);
        }
        // 事务内发应用事件：作废回执（M03 引用联动随 PR-5 订阅；billing 不订阅——费用已同事务作废）
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_PRESCRIPTION_CANCELLED,
                new PrescriptionCancelledPayload(rxNo, rxNo, rx.getPatientId(), rx.getVisitId(), reason)));
        log.info("处方作废：rxNo={}，原状态={}，原因={}", rxNo, status, reason);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PrescriptionVO> list(
            String visitId, Long patientId, String rxNo, String status, int page, int size) {
        // 数据库读操作：四条件任意组合 + id 升序（A.4.3-17 唯一顺序约束）
        Page<Prescription> result = baseMapper.selectPage(
                new Page<>(page + 1, size),
                Wrappers.<Prescription>lambdaQuery()
                        .eq(visitId != null && !visitId.isBlank(), Prescription::getVisitId, visitId)
                        .eq(patientId != null, Prescription::getPatientId, patientId)
                        .eq(rxNo != null && !rxNo.isBlank(), Prescription::getRxNo, rxNo)
                        .eq(status != null && !status.isBlank(), Prescription::getStatus, status)
                        .orderByAsc(Prescription::getId));
        List<Prescription> records = result.getRecords();
        if (records.isEmpty()) {
            // 空页短路：不发起明细 in 批查（空 id 集不出网）
            return PageResult.of(List.of(), page, size, result.getTotal());
        }
        // 数据库读操作：本页明细一次 in 批装载（A.4.3-14 循环单查改批量拒绝 N+1）；prescription_id+id
        //   升序保住每处方内明细相对序（与原逐处方单查 orderByAsc(id) 等价），groupingBy 保组内遇序
        List<Long> rxIds = records.stream().map(Prescription::getId).toList();
        Map<Long, List<PrescriptionItem>> itemsByRx = prescriptionItemMapper
                .selectList(Wrappers.<PrescriptionItem>lambdaQuery()
                        .in(PrescriptionItem::getPrescriptionId, rxIds)
                        .orderByAsc(PrescriptionItem::getPrescriptionId)
                        .orderByAsc(PrescriptionItem::getId))
                .stream()
                .collect(Collectors.groupingBy(PrescriptionItem::getPrescriptionId));
        List<PrescriptionVO> content = records.stream()
                .map(rx -> PrescriptionVO.from(rx, toItemVOs(itemsByRx.getOrDefault(rx.getId(), List.of()))))
                .toList();
        return PageResult.of(content, page, size, result.getTotal());
    }

    /** 就诊号与类型守卫（O 型 14 位 + PR-4 类型白名单 OUTPATIENT/EMERGENCY） */
    private void validateVisitAndType(PrescriptionCreateRequest req) {
        if (!VisitIdValidator.isValid(req.visitId()) || !req.visitId().startsWith(VisitIdValidator.TYPE_OUTPATIENT)) {
            throw new BizException(
                    PharmacyErrorCode.VISIT_ID_MALFORMED, HttpStatus.BAD_REQUEST, "就诊号须为 O 型 14 位：" + req.visitId());
        }
        if (!"OUTPATIENT".equals(req.rxType()) && !"EMERGENCY".equals(req.rxType())) {
            throw new BizException(
                    PharmacyErrorCode.PRESCRIPTION_LINE_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "PR-4 仅支持门诊/急诊开方（DISCHARGE/INTERNET 预留）：" + req.rxType());
        }
    }

    /** 明细行守卫：药品在用、计费关联在位、途径属院内集（PH-1003/1006/1015；数量属请求面校验已前移） */
    private void validateLine(Drug drug, RxItemRequest itemReq) {
        if (drug == null || !"ENABLED".equals(drug.getStatus())) {
            throw new BizException(
                    PharmacyErrorCode.DRUG_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "药品不存在或已停用：drugId=" + (drug == null ? itemReq.drugId() : drug.getId()));
        }
        if (drug.getItemCode() == null || drug.getItemCode().isBlank()) {
            throw new BizException(
                    PharmacyErrorCode.PRESCRIPTION_LINE_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "药品未关联收费项目，不可计费开方：" + drug.getDrugCode());
        }
        if (itemReq.routeCode() != null
                && drug.getRouteCodes() != null
                && !drug.getRouteCodes().isBlank()
                && !List.of(drug.getRouteCodes().split(",")).contains(itemReq.routeCode())) {
            throw new BizException(
                    PharmacyErrorCode.ROUTE_NOT_ALLOWED, HttpStatus.BAD_REQUEST, "给药途径不在药品途径集：" + itemReq.routeCode());
        }
    }

    /**
     * 请求面数量解析守卫（PH-1006，落库前置）：quantity 为 DECIMAL string 承载，非数字串显式拒
     * 400（W-22⑦：禁 NumberFormatException 直穿 500 出契约外形态），逐行判定 &gt;0——0 与负数
     * 均不得生成计费行。解析结果随行返回供明细落库复用，保持单次解析。
     *
     * @param items 处方明细入参，非空
     * @return 与入参同序同位的已解析数量清单（落库直接复用，禁二次 parse）
     * @throws BizException PH-1006（400）：任一行数量非数字串或 ≤0
     */
    private List<BigDecimal> parseQuantities(List<RxItemRequest> items) {
        List<BigDecimal> quantities = new ArrayList<>(items.size());
        for (RxItemRequest itemReq : items) {
            BigDecimal parsed;
            try {
                parsed = new BigDecimal(itemReq.quantity());
            } catch (NumberFormatException e) {
                throw new BizException(
                        PharmacyErrorCode.PRESCRIPTION_LINE_INVALID,
                        HttpStatus.BAD_REQUEST,
                        "处方数量须为数字串：" + itemReq.quantity());
            }
            if (parsed.signum() <= 0) {
                throw new BizException(
                        PharmacyErrorCode.PRESCRIPTION_LINE_INVALID,
                        HttpStatus.BAD_REQUEST,
                        "处方数量须大于 0：" + itemReq.quantity());
            }
            quantities.add(parsed);
        }
        return quantities;
    }

    /** 用法摘要拼装（created 事件计费行与药袋展示共用口径；备注非空追加尾段） */
    private String buildUsageSummary(RxItemRequest itemReq) {
        StringBuilder sb = new StringBuilder();
        if (itemReq.routeCode() != null) {
            sb.append(itemReq.routeCode()).append("，");
        }
        if (itemReq.frequency() != null) {
            sb.append(itemReq.frequency()).append("，");
        }
        if (itemReq.singleDose() != null) {
            sb.append("每次 ").append(itemReq.singleDose()).append("，");
        }
        if (itemReq.days() != null) {
            sb.append("连用 ").append(itemReq.days()).append(" 天");
        }
        if (itemReq.usageNote() != null && !itemReq.usageNote().isBlank()) {
            sb.append("；").append(itemReq.usageNote());
        }
        String summary = sb.toString();
        return summary.isBlank() ? "遵医嘱" : summary;
    }

    /** 处方类别取毒麻最高级（顺序即严重序：TOXIC > NARCOTIC > PSYCHOTIC_I > PSYCHOTIC_II > NORMAL） */
    private String maxNarcotic(String current, String candidate) {
        List<String> order = List.of("NORMAL", "PSYCHOTIC_II", "PSYCHOTIC_I", "NARCOTIC", "TOXIC");
        return order.indexOf(candidate) > order.indexOf(current) ? candidate : current;
    }

    /**
     * 抗菌药授权取最高分级（严重序 SPECIAL &gt; RESTRICTED &gt; UNRESTRICTED）：命中集只查最高分级
     * 对应 grant_type——高分级授权必然覆盖低分级处方行为，避免同方重复校验。词表外非空分级属主数据
     * 脏数据，fail-closed 显式暴露（R1 裁量：抗菌药授权是医疗安全校验，静默跳过即授权失控——
     * IllegalStateException 数据异常口径与 casApprove 竞态同源，人工对账介入）。
     *
     * @param current      当前已聚合的最高分级授权，可空（尚无抗菌药命中）
     * @param antibioClass 明细药品抗菌药分级（drug.antibio_class），可空/NONE=非抗菌药不参与
     * @return 聚合后的最高分级授权；仍无命中返回 null
     * @throws IllegalStateException 词表外非空 antibio_class（主数据异常，人工对账）时触发
     */
    private static String maxAntibioGrant(String current, String antibioClass) {
        if (antibioClass == null || "NONE".equals(antibioClass)) {
            return current;
        }
        String candidate = ANTIBIO_GRANT_BY_CLASS.get(antibioClass);
        if (candidate == null) {
            throw new IllegalStateException("药品抗菌药分级词表外（主数据异常，人工对账）：antibioClass=" + antibioClass);
        }
        if (current == null) {
            return candidate;
        }
        return ANTIBIO_GRANT_SEVERITY.indexOf(candidate) > ANTIBIO_GRANT_SEVERITY.indexOf(current)
                ? candidate
                : current;
    }

    /**
     * 执业授权校验唯一出口（PH-1017，403）：PracticeCheckPort 未过即拒；文案携工号脱敏（等保三级
     * 脱敏口径，禁明文工号出 ProblemDetail），reason 原样透传（授权已过期/无有效记录两态可定位）。
     *
     * @param employeeId 员工 ID（运行态 userId 直作，Task 2 身份链口径），非空
     * @param grantType  授权类型词表值（PRESCRIPTION/ANTIBIO_NONRESTRICT/ANTIBIO_RESTRICT/
     *                   ANTIBIO_SPECIAL/NARCOTIC），非空
     * @throws BizException PH-1017（403）授权未过时触发；建议处理策略：提示医师联系医务授权管理，
     *                      前端禁重试直发
     */
    private void checkGrant(long employeeId, String grantType) {
        PracticeCheckResult result = practiceCheckPort.check(employeeId, grantType);
        if (!result.passed()) {
            log.warn(
                    "开方拒绝：执业授权未过：employeeId（脱敏）={}，grantType={}，reason={}",
                    maskEmployeeId(String.valueOf(employeeId)),
                    grantType,
                    result.reason());
            throw new BizException(
                    PharmacyErrorCode.PRACTICE_NOT_ALLOWED,
                    HttpStatus.FORBIDDEN,
                    "开方执业授权未过（工号 " + maskEmployeeId(String.valueOf(employeeId)) + "）：" + result.reason());
        }
    }

    /**
     * 操作者标识解析为 employeeId（运行态 userId 直作 employeeId，Task 2 身份链口径）：非数字串
     * 显式 PH-1016 拒绝（W-22⑦ 禁裸 parse——NumberFormatException 裸抛即底层异常），门诊侧
     * ClinicOrderServiceImpl OP-1019 同型守卫。
     *
     * @return 员工 ID（OperatorContextHolder 运行态 userId）
     * @throws BizException PH-1016（400）操作者标识非数字（无法定位执业授权主体）时触发
     */
    private static long parseOperatorAsEmployeeId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    PharmacyErrorCode.NUMERIC_FIELD_MALFORMED,
                    HttpStatus.BAD_REQUEST,
                    "操作者标识非数字（无法定位执业授权主体）：" + maskEmployeeId(operator));
        }
        return Long.parseLong(operator);
    }

    /**
     * 工号脱敏（等保三级展示口径）：首尾字符保留、中间星号遮蔽；两位以内整体遮蔽防短串反推。
     *
     * @param employeeId 工号原文，可空
     * @return 脱敏文案（如 301→3***1、3→***），非空
     */
    private static String maskEmployeeId(String employeeId) {
        return employeeId == null || employeeId.length() <= 2
                ? "***"
                : employeeId.charAt(0) + "***" + employeeId.charAt(employeeId.length() - 1);
    }

    /** 签发处方号：R+yyyyMMdd+6 位纳秒尾数（uk_rx_no 兜底并发重号，P1 演示序列与 billing fee_no 同口径） */
    private String nextRxNo() {
        return "R" + LocalDate.now().format(RX_DATE)
                + String.format("%06d", Math.floorMod(System.nanoTime(), 1_000_000L));
    }

    /** 实体明细→出参清单（create 返回面与 list 分页共用映射） */
    private List<PrescriptionItemVO> toItemVOs(List<PrescriptionItem> items) {
        return items.stream().map(PrescriptionItemVO::from).toList();
    }
}
