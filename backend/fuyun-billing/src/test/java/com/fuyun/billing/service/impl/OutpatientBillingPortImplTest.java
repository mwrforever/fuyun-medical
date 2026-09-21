package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.OutpatientBillingPort;
import com.fuyun.billing.api.VisitFeeView;
import com.fuyun.billing.api.VisitRefundCommand;
import com.fuyun.billing.dto.RefundApplyRequest;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.billing.service.IRefundService;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 门诊退费端口转调单测（M03/M13 进程内对接面，Task 6）：OutpatientBillingPortImpl 位于
 * com.fuyun.billing.service.impl——父 POM 规则二 LINE=1.00 包，端口三方法转调行覆盖由本测试承载。
 * 断言口径：逐字转调（禁第二套逻辑）+ 组件映射零漂移 + 引擎异常原样上抛（转调不吞）。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientBillingPortImplTest {

    @Mock
    private IRefundService refundService;

    @Mock
    private IPricingEngineService pricingEngineService;

    @Mock
    private FeeRecordMapper feeRecordMapper;

    @BeforeAll
    static void initTableInfo() {
        // feesByVisit 按 visit_id 定位的 lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeRecord.class);
    }

    private OutpatientBillingPort port() {
        return new OutpatientBillingPortImpl(refundService, pricingEngineService, feeRecordMapper);
    }

    /** 费用行替身（id/状态/来源引用/触发点/金额/结算锚由入参定） */
    private FeeRecord fee(long id, FeeStatus status, String sourceRef, TriggerType triggerPoint, long settlementId) {
        FeeRecord fee = new FeeRecord();
        fee.setId(id);
        fee.setStatus(status);
        fee.setSourceRef(sourceRef);
        fee.setTriggerPoint(triggerPoint);
        fee.setAmount(3000L);
        fee.setSettlementId(settlementId);
        fee.setChargeSource(ChargeSource.MANUAL);
        return fee;
    }

    @Test
    @DisplayName("feesByVisit：按 visit_id 全状态查询逐字映射 VisitFeeView 六组件且 id 升序透传")
    void feesByVisitDelegatesAndMapsAllComponents() {
        when(feeRecordMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        fee(1L, FeeStatus.SETTLED, "OP20260921000001", TriggerType.ORDER_CONFIRMED, 501L),
                        fee(2L, FeeStatus.PENDING, "MANUAL-1", TriggerType.MANUAL, 502L)));

        List<VisitFeeView> views = port().feesByVisit("O20260921000001");

        assertThat(views).hasSize(2);
        assertThat(views.get(0).feeId()).isEqualTo(1L);
        assertThat(views.get(0).status()).isEqualTo("SETTLED");
        assertThat(views.get(0).sourceRef()).isEqualTo("OP20260921000001");
        assertThat(views.get(0).triggerPoint()).isEqualTo("ORDER_CONFIRMED");
        assertThat(views.get(0).amountFen()).isEqualTo(3000L);
        assertThat(views.get(0).settlementId()).isEqualTo(501L);
        assertThat(views.get(1).feeId()).isEqualTo(2L);
        assertThat(views.get(1).status()).isEqualTo("PENDING");
        assertThat(views.get(1).sourceRef()).isEqualTo("MANUAL-1");
        assertThat(views.get(1).triggerPoint()).isEqualTo("MANUAL");
        assertThat(views.get(1).amountFen()).isEqualTo(3000L);
        assertThat(views.get(1).settlementId()).isEqualTo(502L);
    }

    @Test
    @DisplayName("applyRefund：cmd.lines 逐条转 RefundLine(feeId,quantity) 转调 apply 且 refundId 透传返回")
    void applyRefundDelegatesToRefundApplyWithLineConversion() {
        when(refundService.apply(any(RefundApplyRequest.class))).thenReturn(901L);
        VisitRefundCommand cmd = new VisitRefundCommand(
                501L, List.of(new VisitRefundCommand.Line(1L, "1"), new VisitRefundCommand.Line(2L, "1.5")), "门诊退号");

        long refundId = port().applyRefund(cmd);

        assertThat(refundId).isEqualTo(901L);
        ArgumentCaptor<RefundApplyRequest> captor = ArgumentCaptor.forClass(RefundApplyRequest.class);
        verify(refundService).apply(captor.capture());
        RefundApplyRequest request = captor.getValue();
        assertThat(request.settlementId()).isEqualTo(501L);
        assertThat(request.reason()).isEqualTo("门诊退号");
        assertThat(request.lines()).hasSize(2);
        assertThat(request.lines().get(0).feeId()).isEqualTo(1L);
        assertThat(request.lines().get(0).refundQuantity()).isEqualTo(new BigDecimal("1"));
        assertThat(request.lines().get(1).feeId()).isEqualTo(2L);
        assertThat(request.lines().get(1).refundQuantity()).isEqualTo(new BigDecimal("1.5"));
    }

    @Test
    @DisplayName("cancelPendingFee：feeId/reason 原样转调引擎 cancel；引擎抛出异常原样上抛（转调不吞）")
    void cancelPendingFeeDelegatesToPricingEngine() {
        port().cancelPendingFee(7L, "开单作废更正");
        verify(pricingEngineService).cancel(7L, "开单作废更正");

        doThrow(new IllegalStateException("引擎状态竞态")).when(pricingEngineService).cancel(8L, "竞态帧");
        assertThatThrownBy(() -> port().cancelPendingFee(8L, "竞态帧"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("引擎状态竞态");
    }
}
