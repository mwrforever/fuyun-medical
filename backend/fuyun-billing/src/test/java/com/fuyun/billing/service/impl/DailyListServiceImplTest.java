package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.record.DailyListRow;
import com.fuyun.billing.vo.DailyListVO;
import com.fuyun.billing.vo.DailyListVO.CategorySummary;
import com.fuyun.billing.vo.FeeRecordVO;
import com.fuyun.common.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/**
 * 一日清单服务单测（Spec §9 第三层勾稽：明细合计=大类汇总合计=总额；查询 wrapper SQL
 * 守卫钉死 visit_id+billing_date 同口径人群，防清单与汇总两查询人群漂移）。
 */
@ExtendWith(MockitoExtension.class)
class DailyListServiceImplTest {

    /** 住院就诊号夹具 */
    private static final String VISIT = "I2026091600001";

    /** 清单日期夹具 */
    private static final LocalDate DAY = LocalDate.of(2026, 9, 16);

    @Mock
    private FeeRecordMapper feeRecordMapper;

    private DailyListServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeRecord.class);
    }

    @BeforeEach
    void setUp() {
        service = new DailyListServiceImpl(feeRecordMapper);
    }

    private FeeRecord fee(long id, String feeNo, String category, long amount) {
        FeeRecord fee = new FeeRecord();
        fee.setId(id);
        fee.setFeeNo(feeNo);
        fee.setVisitId(VISIT);
        fee.setItemNameSnapshot("项目" + id);
        fee.setAmount(amount);
        fee.setQuantity(BigDecimal.ONE);
        fee.setFeeCategorySnapshot(category);
        fee.setBillingDate(DAY);
        fee.setStatus(FeeStatus.CONFIRMED);
        return fee;
    }

    @Test
    @DisplayName("清单勾稽：大类汇总合计=明细合计=总额（Spec §9 第三层），大类保持 XML ORDER BY 序")
    void dailyListGroupsByCategoryAndReconcilesTotal() {
        when(feeRecordMapper.selectList(any()))
                .thenReturn(
                        List.of(fee(1L, "F1", "西药费", 3000L), fee(2L, "F2", "西药费", 2000L), fee(3L, "F3", "检查费", 1000L)));
        // 聚合以精确入参打桩：visitId/date 传偏即拿不到桩值，勾稽断言失败兜底
        when(feeRecordMapper.dailyListSummary(VISIT, DAY))
                .thenReturn(List.of(new DailyListRow("检查费", 1000L), new DailyListRow("西药费", 5000L)));

        DailyListVO vo = service.dailyList(VISIT, DAY);

        // 三层勾稽断言（明细合计=大类汇总合计=总额）
        long itemsSum = vo.items().stream().mapToLong(FeeRecordVO::amount).sum();
        long categoriesSum =
                vo.categories().stream().mapToLong(CategorySummary::amount).sum();
        assertThat(itemsSum).isEqualTo(6000L);
        assertThat(categoriesSum).isEqualTo(6000L);
        assertThat(vo.totalAmount()).isEqualTo(6000L);
        assertThat(vo.visitId()).isEqualTo(VISIT);
        assertThat(vo.date()).isEqualTo(DAY);
        // 明细行转 FeeRecordVO：行序稳定（id 升序）、快照列原样透传
        assertThat(vo.items()).hasSize(3);
        assertThat(vo.items().get(0).feeNo()).isEqualTo("F1");
        assertThat(vo.items().get(0).itemNameSnapshot()).isEqualTo("项目1");
        assertThat(vo.items().get(0).status()).isEqualTo("CONFIRMED");
        // 大类汇总：组件同源换名（feeCategorySnapshot→feeCategory），顺序保持 XML ORDER BY 唯一序
        assertThat(vo.categories()).extracting(CategorySummary::feeCategory).containsExactly("检查费", "西药费");
        assertThat(vo.categories()).extracting(CategorySummary::amount).containsExactly(1000L, 5000L);
        // 明细查询 SQL 守卫钉死：等值条件必须落在 visit_id+billing_date 列且携带本次就诊号与日期
        // （与 XML 聚合同人群——任一侧口径漂移即勾稽失真，双侧同谓词是三层勾稽成立的前提）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<FeeRecord>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(feeRecordMapper).selectList(captor.capture());
        LambdaQueryWrapper<FeeRecord> wrapper = (LambdaQueryWrapper<FeeRecord>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("visit_id").contains("billing_date");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(VISIT, DAY);
    }

    @Test
    @DisplayName("空日清单：无费用日出空明细/空大类、总额 0（床旁屏按日轮询不报错）")
    void dailyListReturnsEmptyWhenNoFeesForDay() {
        when(feeRecordMapper.selectList(any())).thenReturn(List.of());
        when(feeRecordMapper.dailyListSummary(VISIT, DAY)).thenReturn(List.of());

        DailyListVO vo = service.dailyList(VISIT, DAY);

        assertThat(vo.items()).isEmpty();
        assertThat(vo.categories()).isEmpty();
        assertThat(vo.totalAmount()).isZero();
    }

    @Test
    @DisplayName("就诊号守卫：门诊 O 前缀 BILL-1013 拒（一日清单为住院 FU-M13-04 业务）")
    void dailyListRejectsOutpatientVisitIdAs400() {
        assertThatThrownBy(() -> service.dailyList("O2026091600001", DAY))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.VISIT_ID_MALFORMED));
        verifyNoInteractions(feeRecordMapper);
    }
}
