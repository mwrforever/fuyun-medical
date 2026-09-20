package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.service.IPricingEngineService;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 处方联动费用作废端口单测：谓词与逐行复用引擎 cancel、返回作废计数。 */
@ExtendWith(MockitoExtension.class)
class PrescriptionFeePortImplTest {

    @Mock
    private FeeRecordMapper feeRecordMapper;

    @Mock
    private IPricingEngineService engine;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeRecord.class);
    }

    @Test
    @DisplayName("按 sourceRef 作废处方通道 PENDING 费用：逐行 cancel 且计数回执")
    void cancelPendingBySourceRefCancelsEachRowAndReturnsCount() {
        FeeRecord a = new FeeRecord();
        a.setId(1L);
        FeeRecord b = new FeeRecord();
        b.setId(2L);
        when(feeRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(a, b));

        int cancelled = new PrescriptionFeePortImpl(feeRecordMapper, engine)
                .cancelPendingBySourceRef("R20260918000001", "医生改方");

        assertThat(cancelled).isEqualTo(2);
        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(engine, org.mockito.Mockito.times(2)).cancel(ids.capture(), org.mockito.ArgumentMatchers.eq("医生改方"));
        assertThat(ids.getAllValues()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("无在途费用：计数 0 且零调用（幂等合法）")
    void cancelPendingBySourceRefReturnsZeroWhenNoRows() {
        when(feeRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(new PrescriptionFeePortImpl(feeRecordMapper, engine).cancelPendingBySourceRef("R-X", "作废"))
                .isZero();
        verify(engine, org.mockito.Mockito.never()).cancel(any(long.class), any());
    }
}
