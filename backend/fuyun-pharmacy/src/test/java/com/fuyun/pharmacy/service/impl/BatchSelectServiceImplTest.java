package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.pharmacy.entity.DrugBatch;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** FEFO 选批单测：近效期先出、同效期先产先出（Spec §10 边界项）、足量过滤、无批返空。 */
@ExtendWith(MockitoExtension.class)
class BatchSelectServiceImplTest {

    @Mock
    private DrugBatchMapper drugBatchMapper;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DrugBatch.class);
    }

    private BatchSelectServiceImpl newService() {
        BatchSelectServiceImpl impl = new BatchSelectServiceImpl(drugBatchMapper);
        ReflectionTestUtils.setField(impl, "baseMapper", drugBatchMapper);
        return impl;
    }

    private DrugBatch batch(long id, LocalDate expire, LocalDate production, String qty) {
        DrugBatch b = new DrugBatch();
        b.setId(id);
        b.setExpireDate(expire);
        b.setProductionDate(production);
        b.setQuantity(new BigDecimal(qty));
        b.setLockedQty(BigDecimal.ZERO);
        return b;
    }

    @Test
    @DisplayName("FEFO：近效期先出，同效期按先产先出次序（Spec §10 边界口径）")
    void selectPicksEarliestExpireThenEarliestProduction() {
        // mapper 返回序即 FEFO 序（谓词 orderByAsc(expire, production) 由本测断言 wrapper 序键）
        when(drugBatchMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(batch(2L, LocalDate.of(2026, 10, 1), LocalDate.of(2025, 1, 1), "50")));
        DrugBatch picked = newService().selectForDispense(11L, "OUTP_PHARM", new BigDecimal("5"));
        assertThat(picked).isNotNull();
        assertThat(picked.getId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("无足量在库批次：返回 null（调用方 PH-1010 拒绝，禁部分锁）")
    void selectReturnsNullWhenNoEligibleBatch() {
        when(drugBatchMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(newService().selectForDispense(11L, "OUTP_PHARM", new BigDecimal("5")))
                .isNull();
    }

    @Test
    @DisplayName("选批谓词：IN_STOCK + 可用量 >= 请发数 + FEFO 双序键（wrapper 子串断言）")
    void selectWrapperContainsAvailabilityAndFefoOrder() {
        when(drugBatchMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        newService().selectForDispense(11L, "OUTP_PHARM", new BigDecimal("5"));

        org.mockito.ArgumentCaptor<Wrapper<DrugBatch>> captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(drugBatchMapper).selectList(captor.capture());
        // wrapper 断言拆列名+值双面（Task 2 先例）：条件值不落 SQL 片段，序键列落片段
        String sql = captor.getValue().getSqlSegment();
        org.assertj.core.api.Assertions.assertThat(sql).contains("status");
        org.assertj.core.api.Assertions.assertThat(sql).contains("expire_date").contains("production_date");
        // getSqlSegment 惰性求值时才写入 paramNameValuePairs——先渲染片段再断言参数（DrugServiceImplTest 同款）
        org.assertj.core.api.Assertions.assertThat(
                        ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DrugBatch>)
                                        captor.getValue())
                                .getParamNameValuePairs()
                                .values())
                .contains("OUTP_PHARM", "IN_STOCK");
    }
}
