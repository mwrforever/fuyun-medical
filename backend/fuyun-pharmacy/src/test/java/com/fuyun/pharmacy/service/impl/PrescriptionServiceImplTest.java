package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.billing.api.PrescriptionFeePort;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.dto.PrescriptionCreateRequest;
import com.fuyun.pharmacy.dto.RxItemRequest;
import com.fuyun.pharmacy.entity.Drug;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.PrescriptionItem;
import com.fuyun.pharmacy.mapper.DrugMapper;
import com.fuyun.pharmacy.mapper.PrescriptionItemMapper;
import com.fuyun.pharmacy.mapper.PrescriptionMapper;
import com.fuyun.pharmacy.vo.PrescriptionVO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 处方服务单测（service.impl LINE=1.00 达标件）：开方守卫（就诊号/途径集/计费关联/类型白名单）、
 * rx_no 签发与 CREATED→APPROVED 同事务、作废三分支（未缴费联动费用作废/已缴费拒/并发抢锚）。
 * created/cancelled 事件发布由 Task 4 接线（构造器届时追加 events 参数，本类构造同步补 mock）。
 */
@ExtendWith(MockitoExtension.class)
class PrescriptionServiceImplTest {

    private static final String VISIT = "O2026091800001";

    @Mock
    private PrescriptionMapper prescriptionMapper;

    @Mock
    private PrescriptionItemMapper prescriptionItemMapper;

    @Mock
    private DrugMapper drugMapper;

    @Mock
    private PrescriptionFeePort prescriptionFeePort;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Prescription.class);
    }

    private PrescriptionServiceImpl newService() {
        PrescriptionServiceImpl impl = new PrescriptionServiceImpl(
                prescriptionMapper, prescriptionItemMapper, drugMapper, prescriptionFeePort);
        ReflectionTestUtils.setField(impl, "baseMapper", prescriptionMapper);
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
        when(drugMapper.selectById(11L)).thenReturn(drug());
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);
        when(prescriptionItemMapper.insert(any(PrescriptionItem.class))).thenReturn(1);

        PrescriptionVO vo = impl.create(request(VISIT, "OUTPATIENT", "ORAL"));

        assertThat(vo.rxNo())
                .startsWith("R"
                        + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")))
                .hasSize(1 + 8 + 6); // R+8 位日期+6 位流水（动态年份，禁硬编码）
        assertThat(vo.reviewLevel()).isEqualTo("PASS"); // 预检恒通过级（占位级，P3 接引擎）
        assertThat(vo.status()).isEqualTo("APPROVED"); // CREATED→APPROVED 同事务
        assertThat(vo.items()).hasSize(1);
        assertThat(vo.items().get(0).itemCode()).isEqualTo("C0131230900157"); // 计费行快照来自 drug
        assertThat(vo.items().get(0).usageSummary()).contains("ORAL").contains("TID");
        assertThat(vo.rxCategory()).isEqualTo("NORMAL"); // 毒麻类别派生
        verify(prescriptionMapper).casApprove(100L);
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

        assertThatThrownBy(() -> impl.create(request(VISIT, "OUTPATIENT", "ORAL")))
                .isInstanceOf(IllegalStateException.class);
        verify(prescriptionItemMapper, never()).insert(any(PrescriptionItem.class)); // 明细零落库（异常即整事务回滚语义）
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
        when(drugMapper.selectById(11L)).thenReturn(drug());
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
        when(drugMapper.selectById(11L)).thenReturn(unmapped);
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
        when(drugMapper.selectById(11L)).thenReturn(disabled);
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
        when(drugMapper.selectById(11L)).thenReturn(narcotic);
        when(prescriptionMapper.insert(any(Prescription.class))).thenAnswer(inv -> {
            inv.getArgument(0, Prescription.class).setId(100L);
            return 1;
        });
        when(prescriptionMapper.casApprove(100L)).thenReturn(1);
        when(prescriptionItemMapper.insert(any(PrescriptionItem.class))).thenReturn(1);

        PrescriptionVO vo = impl.create(request(VISIT, "OUTPATIENT", "ORAL"));

        assertThat(vo.rxCategory()).isEqualTo("NARCOTIC"); // maxNarcotic 升级分支：候选严格高于现值才替换
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
    @DisplayName("作废并发抢锚：casCancel 0 行（放行/并发作废抢先）拒 PH-1005 且费用零联动")
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
        verify(prescriptionFeePort, never()).cancelPendingBySourceRef(anyString(), anyString());
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
}
