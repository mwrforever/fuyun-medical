package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.FeeGenerateCommand;
import com.fuyun.billing.entity.FeeOwnershipSplit;
import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.FeeSplitType;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.mapper.FeeOwnershipSplitMapper;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.common.exception.BizException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * 住院计费联动服务单测（P2 PR-1 Task 13，brief 冻结十用例之①–⑦承载面）：入科起费锚+当日床位费/
 * 离散计价两项两行/重复幂等（计价唯一键兜底）/执行确认/停嘱截断/转科切分落行/出院停费标记与日切
 * 跳过。GC24 单测构造范式：MockitoExtension + TableInfoHelper 表信息注册 + 构造器注入
 * collaborator mock。
 */
@ExtendWith(MockitoExtension.class)
class InpatientChargeServiceImplTest {

    private static final Instant AT = Instant.parse("2026-09-25T01:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private FeeOwnershipSplitMapper feeOwnershipSplitMapper;

    @Mock
    private FeeRecordMapper feeRecordMapper;

    @Mock
    private IPricingEngineService engine;

    private InpatientChargeServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeOwnershipSplit.class);
    }

    @BeforeEach
    void setUp() {
        service = new InpatientChargeServiceImpl(feeOwnershipSplitMapper, feeRecordMapper, engine);
    }

    @Test
    @DisplayName("① 入科消费：起费锚点落行（ADMIT_START+ward+时点）+ 当日床位费 PENDING 行命令逐字段")
    void admittedWritesAnchorAndChargesBedFee() {
        when(feeOwnershipSplitMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(engine.generateFromSource(any())).thenReturn(1L);

        service.onVisitAdmitted("I20260925000001", 7L, "W-NEURO", AT);

        // 锚点行断言：类型/归属/时点逐字段
        ArgumentCaptor<FeeOwnershipSplit> splitCaptor = ArgumentCaptor.forClass(FeeOwnershipSplit.class);
        verify(feeOwnershipSplitMapper).insert(splitCaptor.capture());
        FeeOwnershipSplit anchor = splitCaptor.getValue();
        assertThat(anchor.getSplitType()).isEqualTo(FeeSplitType.ADMIT_START);
        assertThat(anchor.getVisitId()).isEqualTo("I20260925000001");
        assertThat(anchor.getPatientId()).isEqualTo(7L);
        assertThat(anchor.getFromWardId()).isNull();
        assertThat(anchor.getToWardId()).isEqualTo("W-NEURO");
        assertThat(anchor.getSplitAt()).isEqualTo(OffsetDateTime.ofInstant(AT, ZoneOffset.UTC));

        // 床位费计价命令断言：DAY_CUTOVER/DURATION/种子码/数量 1/sourceRef=visitId/住院类型
        ArgumentCaptor<FeeGenerateCommand> cmdCaptor = ArgumentCaptor.forClass(FeeGenerateCommand.class);
        verify(engine).generateFromSource(cmdCaptor.capture());
        FeeGenerateCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.patientId()).isEqualTo(7L);
        assertThat(cmd.visitId()).isEqualTo("I20260925000001");
        assertThat(cmd.source()).isEqualTo(ChargeSource.DAY_CUTOVER);
        assertThat(cmd.sourceRef()).isEqualTo("I20260925000001");
        assertThat(cmd.trigger()).isEqualTo(TriggerType.DURATION);
        assertThat(cmd.itemCode()).isEqualTo("IN_BED_DAY");
        assertThat(cmd.quantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(cmd.visitType()).isEqualTo(VisitType.IN);
    }

    @Test
    @DisplayName("① 补面：起费锚点已存在（重投）幂等跳过落行，床位费仍照常计价")
    void admittedDuplicateAnchorSkipsInsertButStillChargesBedFee() {
        when(feeOwnershipSplitMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(engine.generateFromSource(any())).thenReturn(1L);

        service.onVisitAdmitted("I20260925000001", 7L, "W-NEURO", AT);

        verify(feeOwnershipSplitMapper, never()).insert(any(FeeOwnershipSplit.class));
        verify(engine).generateFromSource(any());
    }

    @Test
    @DisplayName("① 补面（R1）：并发重投锚点插入撞唯一索引（DuplicateKey）幂等吞过，床位费仍照常计价")
    void admittedConcurrentAnchorInsertConflictSwallowed() {
        // check-then-insert 读-写间隙：守卫读到 0 行后并行消费已落行，插入被 uk_fee_split_visit_type 拦截
        when(feeOwnershipSplitMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(feeOwnershipSplitMapper.insert(any(FeeOwnershipSplit.class)))
                .thenThrow(new DuplicateKeyException("uk_fee_split_visit_type 唯一冲突"));
        when(engine.generateFromSource(any())).thenReturn(1L);

        assertThatCode(() -> service.onVisitAdmitted("I20260925000001", 7L, "W-NEURO", AT))
                .doesNotThrowAnyException();

        verify(feeOwnershipSplitMapper).insert(any(FeeOwnershipSplit.class));
        verify(engine).generateFromSource(any());
    }

    @Test
    @DisplayName("③ 重复计价幂等（计价唯一键兜底）：BILL-1009 吞过不再上抛，其余行照常")
    void duplicatedDiscretePricingSwallowed() {
        when(engine.generateFromSource(any()))
                .thenThrow(new BizException(BillingErrorCode.DUPLICATE_CHARGING, HttpStatus.CONFLICT, "dup"))
                .thenReturn(2L);

        assertThatCode(() -> service.onOrderCreated("MO2026092500001", "I20260925000001", 7L, twoItems()))
                .doesNotThrowAnyException();
        verify(engine, times(2)).generateFromSource(any());
    }

    @Test
    @DisplayName("② 离散计价两项两行（created 载荷承载）：命令 ORDER_LINKED+ORDER_CONFIRMED+医嘱号逐字段")
    void orderCreatedPricesEachItemDiscretely() throws Exception {
        when(engine.generateFromSource(any())).thenReturn(1L, 2L);

        service.onOrderCreated("MO2026092500001", "I20260925000001", 7L, twoItems());

        ArgumentCaptor<FeeGenerateCommand> captor = ArgumentCaptor.forClass(FeeGenerateCommand.class);
        verify(engine, times(2)).generateFromSource(captor.capture());
        List<FeeGenerateCommand> cmds = captor.getAllValues();
        // 行一：检验费 ×2；行二：检查费 ×1（金额归引擎按价格版本服务端算，载荷无金额）
        assertThat(cmds.get(0).itemCode()).isEqualTo("LAB_CBC");
        assertThat(cmds.get(0).quantity()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(cmds.get(1).itemCode()).isEqualTo("EXM_ChestX");
        assertThat(cmds.get(1).quantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(cmds.get(0).source()).isEqualTo(ChargeSource.ORDER_LINKED);
        assertThat(cmds.get(0).trigger()).isEqualTo(TriggerType.ORDER_CONFIRMED);
        assertThat(cmds.get(0).sourceRef()).isEqualTo("MO2026092500001");
        assertThat(cmds.get(0).visitType()).isEqualTo(VisitType.IN);
    }

    @Test
    @DisplayName("③ 补面：非重复业务失败（BILL-1001 项目缺行）原样上抛走死信留痕，禁静默丢费")
    void nonDuplicatePricingFailureRethrows() throws Exception {
        when(engine.generateFromSource(any()))
                .thenThrow(new BizException(BillingErrorCode.CHARGE_ITEM_NOT_FOUND, HttpStatus.NOT_FOUND, "missing"));

        assertThatThrownBy(() -> service.onOrderCreated("MO2026092500001", "I20260925000001", 7L, twoItems()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.CHARGE_ITEM_NOT_FOUND));
    }

    @Test
    @DisplayName("② 守卫：明细缺 itemCode/quantity 计价要素=不合规帧抛 IllegalStateException 死信留痕")
    void orderCreatedWithoutItemCodeThrows() throws Exception {
        JsonNode badItems = mapper.readTree("[{\"quantity\":\"1\"}]");

        assertThatThrownBy(() -> service.onOrderCreated("MO2026092500001", "I20260925000001", 7L, badItems))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("itemCode");
        verify(engine, never()).generateFromSource(any());
    }

    @Test
    @DisplayName("④ executed 消费：该医嘱 PENDING 行 PENDING→CONFIRMED（直改状态不发事件）")
    void executedConfirmsPendingFees() {
        when(feeRecordMapper.casConfirmByOrder("MO2026092500001", "I20260925000001"))
                .thenReturn(2);

        service.onOrderExecuted("MO2026092500001", "I20260925000001");

        verify(feeRecordMapper).casConfirmByOrder("MO2026092500001", "I20260925000001");
    }

    @Test
    @DisplayName("⑤ stopped 消费：未确认 PENDING 行截断作废（PENDING→CANCELLED）")
    void stoppedCancelsPendingFees() {
        when(feeRecordMapper.casCancelPendingByOrder("MO2026092500001", "I20260925000001"))
                .thenReturn(1);

        service.onOrderStopped("MO2026092500001", "I20260925000001");

        verify(feeRecordMapper).casCancelPendingByOrder("MO2026092500001", "I20260925000001");
    }

    @Test
    @DisplayName("⑥ transferred 消费：归属切分落行（visit/from_ward/to_ward/TRANSFER/split_at）")
    void transferredWritesSplitRow() {
        service.onVisitTransferred("I20260925000001", 7L, "W-NEURO", "W-CARDIO", AT);

        ArgumentCaptor<FeeOwnershipSplit> captor = ArgumentCaptor.forClass(FeeOwnershipSplit.class);
        verify(feeOwnershipSplitMapper).insert(captor.capture());
        FeeOwnershipSplit split = captor.getValue();
        assertThat(split.getSplitType()).isEqualTo(FeeSplitType.TRANSFER);
        assertThat(split.getVisitId()).isEqualTo("I20260925000001");
        assertThat(split.getFromWardId()).isEqualTo("W-NEURO");
        assertThat(split.getToWardId()).isEqualTo("W-CARDIO");
        assertThat(split.getSplitAt()).isEqualTo(OffsetDateTime.ofInstant(AT, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("⑦ discharge-requested 消费：停持续计费标记落行；重复投递幂等跳过（日切跳过出院 visit）")
    void dischargeRequestedWritesStopMarkerIdempotent() {
        // 首投：无标记 → 落 DISCHARGE_STOP 行；重投：标记已存在 → 零插入
        when(feeOwnershipSplitMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 1L);

        service.onDischargeRequested("I20260925000001", 7L, AT);
        service.onDischargeRequested("I20260925000001", 7L, AT);

        ArgumentCaptor<FeeOwnershipSplit> captor = ArgumentCaptor.forClass(FeeOwnershipSplit.class);
        verify(feeOwnershipSplitMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getSplitType()).isEqualTo(FeeSplitType.DISCHARGE_STOP);
    }

    @Test
    @DisplayName("⑦ 补面（R1）：并发重投停费标记插入撞唯一索引（DuplicateKey）幂等吞过")
    void dischargeRequestedConcurrentInsertConflictSwallowed() {
        // check-then-insert 读-写间隙：守卫读到无标记后并行消费已落行，插入被 uk_fee_split_visit_type 拦截
        when(feeOwnershipSplitMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(feeOwnershipSplitMapper.insert(any(FeeOwnershipSplit.class)))
                .thenThrow(new DuplicateKeyException("uk_fee_split_visit_type 唯一冲突"));

        assertThatCode(() -> service.onDischargeRequested("I20260925000001", 7L, AT))
                .doesNotThrowAnyException();

        verify(feeOwnershipSplitMapper).insert(any(FeeOwnershipSplit.class));
    }

    @Test
    @DisplayName("⑦ 床位费日切：全院在院就诊逐一生成（出院标记就诊由 SQL 谓词排除不入人群）")
    void dailyBedChargeGeneratesForInHospitalVisitsOnly() {
        when(feeOwnershipSplitMapper.selectDailyChargeableVisits())
                .thenReturn(List.of(anchor("I20260925000001", 7L), anchor("I20260924000002", 8L)));
        when(engine.generateFromSource(any())).thenReturn(1L);

        int charged = service.dailyBedCharge();

        assertThat(charged).isEqualTo(2);
        verify(engine, times(2)).generateFromSource(any());
    }

    @Test
    @DisplayName("⑦ 补面：日切单就诊当日已计（BILL-1009）幂等跳过续行，计数不含")
    void dailyBedChargeDuplicateVisitSkippedAndBatchContinues() {
        when(feeOwnershipSplitMapper.selectDailyChargeableVisits())
                .thenReturn(List.of(anchor("I20260925000001", 7L), anchor("I20260924000002", 8L)));
        when(engine.generateFromSource(any()))
                .thenThrow(new BizException(BillingErrorCode.DUPLICATE_CHARGING, HttpStatus.CONFLICT, "dup"))
                .thenReturn(1L);

        int charged = service.dailyBedCharge();

        assertThat(charged).isEqualTo(1);
        verify(engine, times(2)).generateFromSource(any());
    }

    @Test
    @DisplayName("⑦ 补面：日切单就诊计价失败（定价不可得）error 留痕续行不阻断全院批次")
    void dailyBedChargeFailedVisitDoesNotAbortBatch() {
        when(feeOwnershipSplitMapper.selectDailyChargeableVisits())
                .thenReturn(List.of(anchor("I20260925000001", 7L), anchor("I20260924000002", 8L)));
        when(engine.generateFromSource(any()))
                .thenThrow(new BizException(BillingErrorCode.PRICING_UNAVAILABLE, HttpStatus.CONFLICT, "no price"))
                .thenReturn(1L);

        int charged = service.dailyBedCharge();

        assertThat(charged).isEqualTo(1);
        verify(engine, times(2)).generateFromSource(any());
    }

    @Test
    @DisplayName("日切空人群：零计价调用零失败（全院无在院/全部已出院标记）")
    void dailyBedChargeWithNoVisitsIsNoop() {
        when(feeOwnershipSplitMapper.selectDailyChargeableVisits()).thenReturn(List.of());

        assertThat(service.dailyBedCharge()).isZero();
        verify(engine, never()).generateFromSource(any());
    }

    /**
     * 锚点夹具（日切人群行）。
     *
     * @param visitId   住院就诊号
     * @param patientId 患者主索引
     * @return ADMIT_START 锚点行
     */
    private FeeOwnershipSplit anchor(String visitId, long patientId) {
        FeeOwnershipSplit row = new FeeOwnershipSplit();
        row.setVisitId(visitId);
        row.setPatientId(patientId);
        row.setSplitType(FeeSplitType.ADMIT_START);
        return row;
    }

    /**
     * 两项明细载荷（检验×2 + 检查×1，V901 id 66 契约子集——itemCode/quantity 计价要素）。
     *
     * @return 明细数组 JSON
     * @throws Exception JSON 解析失败（夹具构造错误）
     */
    private JsonNode twoItems() throws Exception {
        return mapper.readTree(
                "[{\"itemCode\":\"LAB_CBC\",\"quantity\":\"2\"},{\"itemCode\":\"EXM_ChestX\",\"quantity\":1}]");
    }
}
