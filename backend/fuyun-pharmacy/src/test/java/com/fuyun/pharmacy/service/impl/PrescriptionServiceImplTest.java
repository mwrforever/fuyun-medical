package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fuyun.billing.api.PrescriptionFeePort;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
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
import com.fuyun.pharmacy.vo.PrescriptionVO;
import com.fuyun.system.api.PracticeCheckPort;
import com.fuyun.system.api.PracticeCheckResult;
import java.time.ZoneId;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 处方服务单测（service.impl LINE=1.00 达标件）：开方守卫（就诊号/途径集/计费关联/类型白名单）、
 * rx_no 签发与 CREATED→APPROVED 同事务、作废三分支（未缴费联动费用作废/已缴费拒/并发抢锚）、
 * created/cancelled 事件发布锚（事件字面量与冻结载荷断言——CF-5 三方一致的可执行面）。
 */
@ExtendWith(MockitoExtension.class)
class PrescriptionServiceImplTest {

    private static final String VISIT = "O2026091800001";

    /** 北京钟面（时区纪律专项 B 类）：处方号日期段期望与生产技术日切同源口径，禁裸 now() */
    private static final ZoneId BEIJING_TZ = ZoneId.of("Asia/Shanghai");

    @Mock
    private PrescriptionMapper prescriptionMapper;

    @Mock
    private PrescriptionItemMapper prescriptionItemMapper;

    @Mock
    private DrugMapper drugMapper;

    @Mock
    private PrescriptionFeePort prescriptionFeePort;

    @Mock
    private PracticeCheckPort practiceCheckPort;

    @Mock
    private ApplicationEventPublisher events;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Prescription.class);
        // MP 3.5.17 单测范式：list 批量装载谓词断言需手工注册明细实体表信息
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PrescriptionItem.class);
    }

    @BeforeEach
    void setUp() {
        // 运行态 userId 直作 employeeId（Task 2 身份链口径，V704 种子 employee_id 对齐=3）——纵深防御校验主体
        OperatorContextHolder.set("3");
        // 纵深防御 ① 处方权放行公共底座（lenient：作废/分页用例不触达，禁严格桩告警）
        lenient()
                .when(practiceCheckPort.check(3L, "PRESCRIPTION"))
                .thenReturn(new PracticeCheckResult(true, "执业授权有效：PRESCRIPTION"));
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    private PrescriptionServiceImpl newService() {
        PrescriptionServiceImpl impl = new PrescriptionServiceImpl(
                prescriptionMapper, prescriptionItemMapper, drugMapper, prescriptionFeePort, practiceCheckPort, events);
        ReflectionTestUtils.setField(impl, "baseMapper", prescriptionMapper);
        // 链式查询载体：Mockito 桩 mapper 非 MyBatis 真代理，entityClass 须直设（billing/inpatient 同款）
        ReflectionTestUtils.setField(impl, "entityClass", Prescription.class);
        return impl;
    }

    /** 造药：途径集 ORAL/IV、已对照计费项目、普通毒麻类别 */
    private Drug drug() {
        Drug d = new Drug();
        d.setId(11L);
        d.setDrugCode("D-IT-001");
        d.setStatus("ENABLED");
        d.setRouteCodes("ORAL,IV");
        d.setUnit("盒");
        d.setItemCode("C0131230900157");
        d.setNarcoticClass("NORMAL");
        d.setSkinTestFlag(false);
        return d;
    }

    private PrescriptionCreateRequest request(String visitId, String rxType, String routeCode) {
        return new PrescriptionCreateRequest(
                700101L,
                visitId,
                rxType,
                "NEIKE",
                List.of("J06.900"),
                false,
                List.of(new RxItemRequest(11L, "2", "盒", "0.5g", routeCode, "TID", 3, "饭后服")));
    }

    @Test
    @DisplayName("开方成功：rx_no 按 R+yyyyMMdd+流水签发、CREATED→APPROVED 同事务、计费行快照落明细")
    void createSignsBusinessNoAndApprovesInSameTransaction() {
        PrescriptionServiceImpl impl = newService();
        when(drugMapper.selectByIds(List.of(11L))).thenReturn(List.of(drug()));
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);

        PrescriptionVO vo;
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            vo = impl.create(request(VISIT, "OUTPATIENT", "ORAL"));
            // A.4.3-16：明细一次批插（JDBC 批处理 + ASSIGN_ID 自动填充），逐条 insert 通道已下线
            mockedDb.verify(() -> Db.saveBatch(any()));
        }

        assertThat(vo.rxNo())
                // 日期段按北京钟面推导（时区纪律专项 B 类，与生产技术日切同源口径，禁裸 now()）
                .startsWith("R"
                        + java.time.LocalDate.now(BEIJING_TZ)
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")))
                .hasSize(1 + 8 + 6); // R+8 位日期+6 位流水（动态年份，禁硬编码）
        assertThat(vo.reviewLevel()).isEqualTo("PASS"); // 预检恒通过级（占位级，P3 接引擎）
        assertThat(vo.status()).isEqualTo("APPROVED"); // CREATED→APPROVED 同事务
        assertThat(vo.items()).hasSize(1);
        assertThat(vo.items().get(0).itemCode()).isEqualTo("C0131230900157"); // 计费行快照来自 drug
        assertThat(vo.items().get(0).usageSummary()).contains("ORAL").contains("TID");
        assertThat(vo.rxCategory()).isEqualTo("NORMAL"); // 毒麻类别派生
        verify(prescriptionMapper).casApprove(100L);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        PharmacyDomainEvent published = (PharmacyDomainEvent) eventCaptor.getValue();
        assertThat(published.eventType()).isEqualTo(PharmacyMessagingConstants.EVENT_PRESCRIPTION_CREATED);
        PrescriptionCreatedPayload payload = (PrescriptionCreatedPayload) published.payload();
        // 冻结契约：prescriptionId 与 rxNo 同值（主控裁决 3）、计费行 quantity DECIMAL string
        assertThat(payload.prescriptionId()).isEqualTo(payload.rxNo()).isEqualTo(vo.rxNo());
        assertThat(payload.lines()).hasSize(1);
        assertThat(payload.lines().get(0).quantity()).isEqualTo("2");
        assertThat(payload.lines().get(0).usageSummary()).isNotBlank();
    }

    @Test
    @DisplayName("开方放行并发被抢：casApprove 0 行（CREATED 被并发改写）抛 IllegalStateException 同事务回滚（禁静默带 CREATED 生效）")
    void createRejectsWhenApproveCasRaceLostAsIllegalState() {
        PrescriptionServiceImpl impl = newService();
        // 药品读取在放行迁移之后：CAS 失败路径不打药品桩（strict stubs 禁无用打桩）
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(0);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.create(request(VISIT, "OUTPATIENT", "ORAL")))
                    .isInstanceOf(IllegalStateException.class);
            mockedDb.verify(() -> Db.saveBatch(any()), never()); // 明细零落库（异常即整事务回滚语义）
        }
    }

    @Test
    @DisplayName("开方守卫：非 O 型/格式非法就诊号拒 PH-1007")
    void createRejectsMalformedVisitIdAsPh1007() {
        PrescriptionServiceImpl impl = newService();

        assertThatThrownBy(() -> impl.create(request("I2026091800001", "OUTPATIENT", "ORAL")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.VISIT_ID_MALFORMED));
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
    }

    @Test
    @DisplayName("开方守卫：给药途径不在 drug.route_codes 院内集拒 PH-1015")
    void createRejectsRouteOutsideDrugRouteSetAsPh1015() {
        PrescriptionServiceImpl impl = newService();
        when(drugMapper.selectByIds(List.of(11L))).thenReturn(List.of(drug()));
        // 药品行守卫在主行落库/放行迁移之后触发（同事务，异常即整体回滚）：机械补齐前置写桩
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);

        assertThatThrownBy(() -> impl.create(request(VISIT, "OUTPATIENT", "IM")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.ROUTE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("开方守卫：药品未关联收费项目（不可计费）拒 PH-1006")
    void createRejectsDrugWithoutChargeItemAsPh1006() {
        PrescriptionServiceImpl impl = newService();
        Drug unmapped = drug();
        unmapped.setItemCode(null);
        when(drugMapper.selectByIds(List.of(11L))).thenReturn(List.of(unmapped));
        // 药品行守卫在主行落库/放行迁移之后触发（同事务，异常即整体回滚）：机械补齐前置写桩
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);

        assertThatThrownBy(() -> impl.create(request(VISIT, "OUTPATIENT", "ORAL")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_LINE_INVALID));
    }

    @Test
    @DisplayName("开方守卫：DISCHARGE/INTERNET 类型白名单外拒 PH-1006（PR-4 仅门诊/急诊）")
    void createRejectsReservedRxTypeAsPh1006() {
        PrescriptionServiceImpl impl = newService();

        assertThatThrownBy(() -> impl.create(request(VISIT, "DISCHARGE", "ORAL")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_LINE_INVALID));
    }

    @Test
    @DisplayName("未缴费作废：PENDING_FEE 先联动 billing 费用作废再 CAS 终态（同事务）")
    void cancelPendingFeeInvokesBillingPortThenMarksCancelled() {
        PrescriptionServiceImpl impl = newService();
        Prescription rx = new Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        rx.setPatientId(700101L);
        rx.setVisitId(VISIT);
        rx.setStatus("PENDING_FEE");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionFeePort.cancelPendingBySourceRef("R20260918000001", "医生改方"))
                .thenReturn(2);
        when(prescriptionMapper.casCancel(100L, "医生改方")).thenReturn(1);

        impl.cancel("R20260918000001", "医生改方");

        verify(prescriptionFeePort).cancelPendingBySourceRef("R20260918000001", "医生改方");
        verify(prescriptionMapper).casCancel(100L, "医生改方");

        // 作废回执事件：字面量与冻结载荷 reason 断言（billing 不订阅，M03 联动随 PR-5）
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        PharmacyDomainEvent published = (PharmacyDomainEvent) eventCaptor.getValue();
        assertThat(published.eventType()).isEqualTo(PharmacyMessagingConstants.EVENT_PRESCRIPTION_CANCELLED);
        PrescriptionCancelledPayload payload = (PrescriptionCancelledPayload) published.payload();
        assertThat(payload.prescriptionId()).isEqualTo(payload.rxNo()).isEqualTo("R20260918000001");
        assertThat(payload.reason()).isEqualTo("医生改方");
    }

    @Test
    @DisplayName("作废 TOCTOU 收口：读态 APPROVED、读后并发迁移 PENDING_FEE 被 casCancel 命中，port 作废仍发生")
    void cancelStillInvokesBillingPortWhenPendingFeeMigratesConcurrentlyAfterRead() {
        PrescriptionServiceImpl impl = newService();
        Prescription rx = new Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        rx.setPatientId(700101L);
        rx.setVisitId(VISIT);
        // 读态 APPROVED：fee.created 消费（APPROVED→PENDING_FEE）尚未到达，读后并发抢先迁移
        rx.setStatus("APPROVED");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        // casCancel 谓词 status IN ('APPROVED','PENDING_FEE') 恰好命中并发迁移后的 PENDING_FEE
        when(prescriptionMapper.casCancel(100L, "医生改方")).thenReturn(1);

        impl.cancel("R20260918000001", "医生改方");

        // 资金路径收口：billing 侧 PENDING 费用行必须随本事务作废，否则可经收费窗口结算
        verify(prescriptionFeePort).cancelPendingBySourceRef("R20260918000001", "医生改方");
        verify(prescriptionMapper).casCancel(100L, "医生改方");
    }

    @Test
    @DisplayName("已缴费拒作废：PENDING_DISPENSE 拒 PH-1014 并引导退药/退费链")
    void cancelRejectsChargedRxAsPh1014() {
        PrescriptionServiceImpl impl = newService();
        Prescription rx = new Prescription();
        rx.setStatus("PENDING_DISPENSE");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        assertThatThrownBy(() -> impl.cancel("R20260918000001", "患者要求"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RX_CANCEL_BLOCKED_AFTER_CHARGE));
        verify(prescriptionFeePort, never()).cancelPendingBySourceRef(anyString(), anyString());
    }

    @Test
    @DisplayName("终态拒作废：DISPENSED 拒 PH-1005")
    void cancelRejectsDispensedRxAsPh1005() {
        PrescriptionServiceImpl impl = newService();
        Prescription rx = new Prescription();
        rx.setStatus("DISPENSED");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        assertThatThrownBy(() -> impl.cancel("R20260918000001", "患者要求"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("开方守卫：数量非正数拒 PH-1006（0 与负数均不得生成计费行）")
    void createRejectsNonPositiveQuantityAsPh1006() {
        PrescriptionServiceImpl impl = newService();
        // 数量守卫在读库前置的请求面校验段：不打药品桩（strict stubs 禁无用打桩）
        PrescriptionCreateRequest badQty = new PrescriptionCreateRequest(
                700101L,
                VISIT,
                "OUTPATIENT",
                "NEIKE",
                List.of("J06.900"),
                false,
                List.of(new RxItemRequest(11L, "0", "盒", "0.5g", "ORAL", "TID", 3, "饭后服")));

        assertThatThrownBy(() -> impl.create(badQty))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_LINE_INVALID));
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
    }

    @Test
    @DisplayName("开方守卫：停用/缺行药品拒 PH-1003（validateLine 首段分支）")
    void createRejectsDisabledDrugAsPh1003() {
        PrescriptionServiceImpl impl = newService();
        Drug disabled = drug();
        disabled.setStatus("DISABLED");
        when(drugMapper.selectByIds(List.of(11L))).thenReturn(List.of(disabled));
        // 药品行守卫在主行落库/放行迁移之后触发（同事务，异常即整体回滚）：机械补齐前置写桩
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);

        assertThatThrownBy(() -> impl.create(request(VISIT, "OUTPATIENT", "ORAL")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DRUG_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("处方类别派生：行内毒麻类别取最高级（NARCOTIC 抬升 NORMAL 处方为麻方口径）")
    void createDerivesRxCategoryFromHighestNarcoticClass() {
        PrescriptionServiceImpl impl = newService();
        Drug narcotic = drug();
        narcotic.setNarcoticClass("NARCOTIC");
        when(drugMapper.selectByIds(List.of(11L))).thenReturn(List.of(narcotic));
        // 麻精命中 → 纵深防御 ② 追加 NARCOTIC 授权校验（Task 9 接线后既有用例同步演进）
        when(practiceCheckPort.check(3L, "NARCOTIC")).thenReturn(new PracticeCheckResult(true, "执业授权有效：NARCOTIC"));
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);

        PrescriptionVO vo;
        try (MockedStatic<Db> ignored = Mockito.mockStatic(Db.class)) {
            vo = impl.create(request(VISIT, "OUTPATIENT", "ORAL"));
        }

        assertThat(vo.rxCategory()).isEqualTo("NARCOTIC"); // maxNarcotic 升级分支：候选严格高于现值才替换
    }

    /** 造药：限制级抗菌药（antibio_class=RESTRICTED → 命中 ANTIBIO_RESTRICT 授权） */
    private Drug antibioDrug() {
        Drug d = drug();
        d.setAntibioClass("RESTRICTED");
        return d;
    }

    /** 造药：特殊使用级抗菌药（antibio_class=SPECIAL → 命中最高分级 ANTIBIO_SPECIAL 授权） */
    private Drug specialAntibioDrug() {
        Drug d = drug();
        d.setId(13L);
        d.setDrugCode("D-IT-003");
        d.setAntibioClass("SPECIAL");
        return d;
    }

    /** 造药：麻醉类药品（narcotic_class=NARCOTIC → 命中 NARCOTIC 授权，非抗菌药） */
    private Drug narcoticDrug() {
        Drug d = drug();
        d.setId(12L);
        d.setDrugCode("D-IT-002");
        d.setNarcoticClass("NARCOTIC");
        return d;
    }

    /** 造双行开方请求：限制级抗菌药 + 麻精药各一行（纵深防御 ② 命中集用例） */
    private PrescriptionCreateRequest antibioNarcoticRequest() {
        return new PrescriptionCreateRequest(
                700101L,
                VISIT,
                "OUTPATIENT",
                "NEIKE",
                List.of("J06.900"),
                false,
                List.of(
                        new RxItemRequest(11L, "2", "盒", "0.5g", "ORAL", "TID", 3, "饭后服"),
                        new RxItemRequest(12L, "1", "盒", "0.25g", "ORAL", "BID", 5, null)));
    }

    @Test
    @DisplayName("开方纵深防御①：处方权（PRESCRIPTION）授权未过拒 PH-1017（403）且零写（裁决 9/Spec :226）")
    void createRejectsWhenPrescriptionGrantMissing() {
        PrescriptionServiceImpl impl = newService();
        when(practiceCheckPort.check(3L, "PRESCRIPTION"))
                .thenReturn(new PracticeCheckResult(false, "无有效执业授权记录：PRESCRIPTION"));

        assertThatThrownBy(() -> impl.create(request(VISIT, "OUTPATIENT", "ORAL")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PharmacyErrorCode.PRACTICE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        // 零写：授权校验前置于一切落库
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
    }

    @Test
    @DisplayName("开方纵深防御②：按命中集三次校验（PRESCRIPTION/ANTIBIO_RESTRICT/NARCOTIC）全过放行，任一未过拒 PH-1017")
    void createChecksAntibioAndNarcoticGrantsByTopClass() {
        PrescriptionServiceImpl impl = newService();
        when(drugMapper.selectByIds(List.of(11L, 12L))).thenReturn(List.of(antibioDrug(), narcoticDrug()));
        when(practiceCheckPort.check(3L, "ANTIBIO_RESTRICT"))
                .thenReturn(new PracticeCheckResult(true, "执业授权有效：ANTIBIO_RESTRICT"));
        when(practiceCheckPort.check(3L, "NARCOTIC")).thenReturn(new PracticeCheckResult(true, "执业授权有效：NARCOTIC"));
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);

        try (MockedStatic<Db> ignored = Mockito.mockStatic(Db.class)) {
            impl.create(antibioNarcoticRequest());
        }
        // 命中集三次校验：①处方权 + ②抗菌药最高分级（RESTRICTED）+ ②麻精命中（纯普通药仅 ① 一次）
        verify(practiceCheckPort).check(3L, "PRESCRIPTION");
        verify(practiceCheckPort).check(3L, "ANTIBIO_RESTRICT");
        verify(practiceCheckPort).check(3L, "NARCOTIC");

        // 任一未过即拒：抗菌药授权翻 false 复跑 → PH-1017（403）且明细零落库（事务整体回滚语义）
        when(practiceCheckPort.check(3L, "ANTIBIO_RESTRICT"))
                .thenReturn(new PracticeCheckResult(false, "授权已过期：ANTIBIO_RESTRICT"));
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.create(antibioNarcoticRequest()))
                    .isInstanceOfSatisfying(BizException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(PharmacyErrorCode.PRACTICE_NOT_ALLOWED);
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
            mockedDb.verify(() -> Db.saveBatch(any()), never());
        }
    }

    @Test
    @DisplayName("开方纵深防御②命中集收敛：同方多级抗菌药（UNRESTRICTED+SPECIAL+RESTRICTED）仅查最高分级 ANTIBIO_SPECIAL（低分级不重复校验且不降级）")
    void createChecksOnlyTopAntibioGrantForMultiAntibioPrescription() {
        PrescriptionServiceImpl impl = newService();
        Drug unrestricted = drug();
        unrestricted.setAntibioClass("UNRESTRICTED");
        // 低分级后置：严重序比较 false 分支（候选低于已聚合最高级时保持不降级）
        Drug restricted = drug();
        restricted.setId(14L);
        restricted.setDrugCode("D-IT-004");
        restricted.setAntibioClass("RESTRICTED");
        when(drugMapper.selectByIds(List.of(11L, 13L, 14L)))
                .thenReturn(List.of(unrestricted, specialAntibioDrug(), restricted));
        when(practiceCheckPort.check(3L, "ANTIBIO_SPECIAL"))
                .thenReturn(new PracticeCheckResult(true, "执业授权有效：ANTIBIO_SPECIAL"));
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);
        PrescriptionCreateRequest multiAntibio = new PrescriptionCreateRequest(
                700101L,
                VISIT,
                "OUTPATIENT",
                "NEIKE",
                List.of("J06.900"),
                false,
                List.of(
                        new RxItemRequest(11L, "1", "盒", "0.5g", "ORAL", "TID", 3, null),
                        new RxItemRequest(13L, "1", "支", "0.1g", "IV", "QD", 2, null),
                        new RxItemRequest(14L, "1", "盒", "0.5g", "ORAL", "TID", 3, null)));

        try (MockedStatic<Db> ignored = Mockito.mockStatic(Db.class)) {
            impl.create(multiAntibio);
        }
        // 命中集只查最高分级：低分级（NONRESTRICT/RESTRICT）不重复校验（高分级授权覆盖低分级处方行为）
        verify(practiceCheckPort).check(3L, "PRESCRIPTION");
        verify(practiceCheckPort).check(3L, "ANTIBIO_SPECIAL");
        verify(practiceCheckPort, never()).check(eq(3L), eq("ANTIBIO_NONRESTRICT"));
        verify(practiceCheckPort, never()).check(eq(3L), eq("ANTIBIO_RESTRICT"));
        verify(practiceCheckPort, never()).check(eq(3L), eq("NARCOTIC"));
    }

    @Test
    @DisplayName("开方批查锚定：多药品处方药品恰一次批查（键集=请求 drugId 去重遇序）且逐行 selectById 零触达（A.4.3-14）")
    void createLoadsAllDrugsInExactlyOneBatchedQueryWithoutRowLevelPointLookups() {
        PrescriptionServiceImpl impl = newService();
        Drug second = drug();
        second.setId(12L);
        second.setDrugCode("D-IT-002");
        when(drugMapper.selectByIds(List.of(11L, 12L))).thenReturn(List.of(drug(), second));
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);
        PrescriptionCreateRequest twoLine = new PrescriptionCreateRequest(
                700101L,
                VISIT,
                "OUTPATIENT",
                "NEIKE",
                List.of("J06.900"),
                false,
                List.of(
                        new RxItemRequest(11L, "2", "盒", "0.5g", "ORAL", "TID", 3, "饭后服"),
                        new RxItemRequest(12L, "1", "盒", "0.25g", "ORAL", "BID", 5, null)));

        try (MockedStatic<Db> ignored = Mockito.mockStatic(Db.class)) {
            impl.create(twoLine);
        }
        // N+1 消除锚定：两行明细恰一次批查（键集=请求 drugId 去重遇序），旧逐行点查通道零触达
        verify(drugMapper, times(1)).selectByIds(List.of(11L, 12L));
        verify(drugMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("同药多行等价锚定：重复 drugId 恰一次装载，两行明细快照逐字段与单查装载一致（行序/数量/用法分行保持）")
    void createKeepsPerLineSnapshotEquivalenceWhenSameDrugAppearsInMultipleLines() {
        PrescriptionServiceImpl impl = newService();
        when(drugMapper.selectByIds(List.of(11L))).thenReturn(List.of(drug()));
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);
        // 同药不同频次两行：第二行 unit 缺省验证 drug.unit 兜底（批查 Map 取行与单查同源快照）
        PrescriptionCreateRequest dup = new PrescriptionCreateRequest(
                700101L,
                VISIT,
                "OUTPATIENT",
                "NEIKE",
                List.of("J06.900"),
                false,
                List.of(
                        new RxItemRequest(11L, "2", "盒", "0.5g", "ORAL", "TID", 3, "饭后服"),
                        new RxItemRequest(11L, "1", null, "0.25g", "IV", "BID", 5, null)));

        PrescriptionVO vo;
        try (MockedStatic<Db> ignored = Mockito.mockStatic(Db.class)) {
            vo = impl.create(dup);
        }

        assertThat(vo.items()).hasSize(2);
        // 两行共享同一药品快照（drugCode/itemCode 快照与 unit 兜底来源一致），请求面字段分行保持
        assertThat(vo.items().get(0).drugId()).isEqualTo(11L);
        assertThat(vo.items().get(0).drugCode()).isEqualTo("D-IT-001");
        assertThat(vo.items().get(0).itemCode()).isEqualTo("C0131230900157");
        assertThat(vo.items().get(0).quantity()).isEqualTo("2");
        assertThat(vo.items().get(0).unit()).isEqualTo("盒");
        assertThat(vo.items().get(0).frequency()).isEqualTo("TID");
        assertThat(vo.items().get(1).drugId()).isEqualTo(11L);
        assertThat(vo.items().get(1).drugCode()).isEqualTo("D-IT-001");
        assertThat(vo.items().get(1).itemCode()).isEqualTo("C0131230900157");
        assertThat(vo.items().get(1).quantity()).isEqualTo("1");
        assertThat(vo.items().get(1).unit()).isEqualTo("盒"); // unit 缺省行兜底取 drug.unit（同源快照）
        assertThat(vo.items().get(1).frequency()).isEqualTo("BID");
        // 重复 drugId 恰一次装载（键集去重天然覆盖同药多行）
        verify(drugMapper, times(1)).selectByIds(List.of(11L));
        // created 事件计费行：两行各自数量与用法摘要（不因同药合并）
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        PharmacyDomainEvent published = (PharmacyDomainEvent) eventCaptor.getValue();
        PrescriptionCreatedPayload payload = (PrescriptionCreatedPayload) published.payload();
        assertThat(payload.lines()).hasSize(2);
        assertThat(payload.lines().get(0).quantity()).isEqualTo("2");
        assertThat(payload.lines().get(1).quantity()).isEqualTo("1");
        assertThat(payload.lines().get(0).usageSummary())
                .isNotEqualTo(payload.lines().get(1).usageSummary());
    }

    @Test
    @DisplayName("开方空明细守卫（契约外形态兜底）：空 items 药品零装载（空键集零查询），主行仍落库生效+发布空计费行事件")
    void createSkipsDrugBatchLoadWhenItemsEmpty() {
        PrescriptionServiceImpl impl = newService();
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);
        // 空明细=HTTP 面 @NotEmpty 已拒的契约外形态（service 层直调可触达）：药品键集空集守卫兜底
        PrescriptionCreateRequest emptyItems = new PrescriptionCreateRequest(
                700101L, VISIT, "OUTPATIENT", "NEIKE", List.of("J06.900"), false, List.of());

        PrescriptionVO vo;
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            vo = impl.create(emptyItems);
            // 空明细落库形态：明细批插通道仍触达（空行集批插），无逐条 insert
            mockedDb.verify(() -> Db.saveBatch(any()));
        }

        // 空键集守卫语义：药品零装载零查询；主行照常 CREATED→APPROVED 生效（空明细不阻断开方主链）
        verify(drugMapper, never()).selectByIds(any());
        assertThat(vo.status()).isEqualTo("APPROVED");
        assertThat(vo.items()).isEmpty();
        assertThat(vo.rxCategory()).isEqualTo("NORMAL"); // 无明细命中→类别保持初始 NORMAL
        assertThat(vo.skinTestRequired()).isFalse();
        // created 事件仍发布且计费行为空集（M-4 唯一携带源契约不因契约外形态破缺）
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        PharmacyDomainEvent published = (PharmacyDomainEvent) eventCaptor.getValue();
        assertThat(published.eventType()).isEqualTo(PharmacyMessagingConstants.EVENT_PRESCRIPTION_CREATED);
        PrescriptionCreatedPayload payload = (PrescriptionCreatedPayload) published.payload();
        assertThat(payload.lines()).isEmpty();
    }

    @Test
    @DisplayName("批查缺行错误语义等价：部分药品缺行拒 PH-1003（409）+ 文案逐字等于逐行点查口径（drugId 取请求行）")
    void createRejectsMissingDrugRowFromBatchLoadWithIdenticalErrorSemantics() {
        PrescriptionServiceImpl impl = newService();
        // 批查仅回 11 号药：12 号行缺行（Map 无键取 null 行）须走与逐行点查缺行同一分支
        when(drugMapper.selectByIds(List.of(11L, 12L))).thenReturn(List.of(drug()));
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);
        PrescriptionCreateRequest partial = new PrescriptionCreateRequest(
                700101L,
                VISIT,
                "OUTPATIENT",
                "NEIKE",
                List.of("J06.900"),
                false,
                List.of(
                        new RxItemRequest(11L, "2", "盒", "0.5g", "ORAL", "TID", 3, null),
                        new RxItemRequest(12L, "1", "盒", "0.25g", "ORAL", "BID", 5, null)));

        // 错误码→HTTP 态→文案三重逐字等价：与旧 selectById 返回 null 同一分支（行序不变，首
        // 个无效行报错——第 1 行命中后第 2 行缺行仍精确报 12 号行）
        assertThatThrownBy(() -> impl.create(partial)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(PharmacyErrorCode.DRUG_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo("药品不存在或已停用：drugId=12");
        });
    }

    @Test
    @DisplayName("开方纵深防御守卫：抗菌药分级词表外（主数据脏数据）fail-closed 拒开——IllegalStateException 数据异常显式暴露，禁静默跳过授权校验")
    void createRejectsUnknownAntibioClassAsDataAnomaly() {
        PrescriptionServiceImpl impl = newService();
        Drug dirty = drug();
        dirty.setAntibioClass("WIDE_SPECTRUM"); // 词表外（AntibacterialClass 四值外）
        when(drugMapper.selectByIds(List.of(11L))).thenReturn(List.of(dirty));
        // 聚合发生在主行落库/放行迁移之后（同事务，异常即整体回滚）：机械补齐前置写桩
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);

        assertThatThrownBy(() -> impl.create(request(VISIT, "OUTPATIENT", "ORAL")))
                .isInstanceOfSatisfying(
                        IllegalStateException.class,
                        e -> assertThat(e.getMessage()).contains("词表外").contains("WIDE_SPECTRUM"));
    }

    @Test
    @DisplayName("开方纵深防御守卫：操作者标识非数字（无法定位执业授权主体）拒 PH-1016（W-22⑦ 禁裸 parse，工号脱敏出文案）")
    void createRejectsUnparsableOperatorAsPh1016() {
        PrescriptionServiceImpl impl = newService();
        OperatorContextHolder.set("doctor-x");

        assertThatThrownBy(() -> impl.create(request(VISIT, "OUTPATIENT", "ORAL")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PharmacyErrorCode.NUMERIC_FIELD_MALFORMED);
                    assertThat(e.getMessage()).contains("d***x"); // 工号脱敏（首尾保留中间遮蔽）
                });
        verify(practiceCheckPort, never()).check(anyLong(), anyString());
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
    }

    @Test
    @DisplayName("作废缺行：未知处方号拒 PH-1004（404）")
    void cancelRejectsUnknownRxAsPh1004() {
        PrescriptionServiceImpl impl = newService();
        when(prescriptionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> impl.cancel("R20260918000001", "患者要求"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_NOT_FOUND));
        verify(prescriptionFeePort, never()).cancelPendingBySourceRef(anyString(), anyString());
    }

    @Test
    @DisplayName("作废并发抢锚：casCancel 0 行（放行/并发作废抢先）拒 PH-1005（port 联动随事务回滚不落）")
    void cancelRejectsWhenConcurrentCancelWinsCasRace() {
        PrescriptionServiceImpl impl = newService();
        Prescription rx = new Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        rx.setStatus("APPROVED");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casCancel(100L, "医生改方")).thenReturn(0);

        assertThatThrownBy(() -> impl.cancel("R20260918000001", "医生改方"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_STATE_NOT_ALLOWED));
        // port 无条件联动（堵 TOCTOU 资金窗口）：CAS 失败抛异常整事务回滚，联动与作废一体不落
        verify(prescriptionFeePort).cancelPendingBySourceRef("R20260918000001", "医生改方");
    }

    @Test
    @DisplayName("分页查询：四条件组合转 VO 分页出参（content/total 透传，明细随行装载）")
    void listReturnsPagedContentWithItems() {
        PrescriptionServiceImpl impl = newService();
        Prescription rx = new Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        rx.setStatus("APPROVED");
        Page<Prescription> page = new Page<>(1, 20);
        page.setRecords(List.of(rx));
        page.setTotal(1);
        when(prescriptionMapper.selectPage(any(), any())).thenReturn(page);
        PrescriptionItem item = new PrescriptionItem();
        item.setId(1L);
        item.setPrescriptionId(100L);
        when(prescriptionItemMapper.selectList(any())).thenReturn(List.of(item));

        PageResult<PrescriptionVO> result = impl.list(VISIT, 700101L, null, "APPROVED", 0, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).rxNo()).isEqualTo("R20260918000001");
        assertThat(result.content().get(0).items()).hasSize(1);
    }

    @Test
    @DisplayName("开方守卫：数量非数字串拒 PH-1006（400 显式拒，禁 NumberFormatException 直穿 500）")
    void createRejectsNonNumericQuantityAsPh1006() {
        PrescriptionServiceImpl impl = newService();
        // 数量守卫在读库前置的请求面校验段：不打药品桩（strict stubs 禁无用打桩）
        PrescriptionCreateRequest badQty = new PrescriptionCreateRequest(
                700101L,
                VISIT,
                "OUTPATIENT",
                "NEIKE",
                List.of("J06.900"),
                false,
                List.of(new RxItemRequest(11L, "2盒", "盒", "0.5g", "ORAL", "TID", 3, "饭后服")));

        assertThatThrownBy(() -> impl.create(badQty))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_LINE_INVALID));
        verify(prescriptionMapper, never()).insert(any(Prescription.class));
    }

    @Test
    @DisplayName("list 批量装载与逐行装载等价：一次 in 批查明细按处方分组（组内相对序与逐行单查一致）")
    void listLoadsItemsInOneBatchedQueryWithPerPrescriptionOrderPreserved() {
        PrescriptionServiceImpl impl = newService();
        Prescription rx1 = new Prescription();
        rx1.setId(100L);
        rx1.setRxNo("R20260918000001");
        rx1.setStatus("APPROVED");
        Prescription rx2 = new Prescription();
        rx2.setId(101L);
        rx2.setRxNo("R20260918000002");
        rx2.setStatus("APPROVED");
        Page<Prescription> page = new Page<>(1, 20);
        page.setRecords(List.of(rx1, rx2));
        page.setTotal(2);
        when(prescriptionMapper.selectPage(any(), any())).thenReturn(page);
        // 批查返回按 prescription_id+id 升序（与实现约定一致）：100 两行、101 一行
        when(prescriptionItemMapper.selectList(any()))
                .thenReturn(List.of(rxItem(1L, 100L), rxItem(2L, 100L), rxItem(3L, 101L)));

        PageResult<PrescriptionVO> result = impl.list(null, 700101L, null, null, 0, 20);

        assertThat(result.total()).isEqualTo(2);
        // 逐行装载同构：每处方仅见自己名下明细，组内相对序保持（原单查 orderByAsc(id) 语义）
        assertThat(result.content().get(0).items()).hasSize(2);
        assertThat(result.content().get(0).items().get(0).id()).isEqualTo(1L);
        assertThat(result.content().get(0).items().get(1).id()).isEqualTo(2L);
        assertThat(result.content().get(1).items()).hasSize(1);
        assertThat(result.content().get(1).items().get(0).id()).isEqualTo(3L);
        // N+1 消除断言：明细仅一次批查，谓词落 prescription_id IN（本页处方 id 集）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PrescriptionItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(prescriptionItemMapper).selectList(captor.capture());
        LambdaQueryWrapper<PrescriptionItem> wrapper = (LambdaQueryWrapper<PrescriptionItem>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("IN");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(100L, 101L);
    }

    @Test
    @DisplayName("list 空页短路：无记录时不发起明细批查（空 id 集不出网）")
    void listShortCircuitsItemBatchLoadWhenPageEmpty() {
        PrescriptionServiceImpl impl = newService();
        Page<Prescription> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(prescriptionMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<PrescriptionVO> result = impl.list(null, null, null, null, 0, 20);

        assertThat(result.total()).isZero();
        assertThat(result.content()).isEmpty();
        verify(prescriptionItemMapper, never()).selectList(any());
    }

    /** 造处方明细行（id 与所属处方 id 指定，list 分组序断言用） */
    private PrescriptionItem rxItem(long id, long prescriptionId) {
        PrescriptionItem item = new PrescriptionItem();
        item.setId(id);
        item.setPrescriptionId(prescriptionId);
        item.setItemCode("C0131230900157");
        return item;
    }
}
