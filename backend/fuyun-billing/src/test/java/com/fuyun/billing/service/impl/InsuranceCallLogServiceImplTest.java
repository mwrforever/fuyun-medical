package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.entity.InsuranceCallLog;
import com.fuyun.billing.enums.InsuranceCallStatus;
import com.fuyun.billing.mapper.InsuranceCallLogMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 医保调用留痕实现单测（billing.insurance_call_log，方案 3.2 两级日志之业务级）：留痕行落库
 * （模拟 SUCCESS 直落/P5 两段式 INIT 先行，回执摘要钳 512）、补偿重试状态守卫（BILL-1026 仅
 * TIMEOUT/FAILED）与成功迁移（COMPENSATED+结论留痕）。
 */
@ExtendWith(MockitoExtension.class)
class InsuranceCallLogServiceImplTest {

    /** 门诊就诊号夹具 */
    private static final String VISIT = "O2026091700001";

    @Mock
    private InsuranceCallLogMapper callLogMapper;

    private InsuranceCallLogServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 单测容器外手动初始化实体表信息（lambda 列名解析依赖 TableInfo，模块内既有 ServiceImpl 单测同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), InsuranceCallLog.class);
    }

    @BeforeEach
    void setUp() {
        service = new InsuranceCallLogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", callLogMapper);
        ReflectionTestUtils.setField(service, "entityClass", InsuranceCallLog.class);
    }

    /** 留痕行夹具（status 由用例按 INIT/SUCCESS 两形态各自置定） */
    private InsuranceCallLog row(InsuranceCallStatus status, String responseDigest) {
        InsuranceCallLog row = new InsuranceCallLog();
        row.setTxnCode("2102");
        row.setVisitId(VISIT);
        row.setRequestDigest("preview|total=10000");
        row.setResponseDigest(responseDigest);
        row.setStatus(status);
        return row;
    }

    @Test
    @DisplayName("留痕落库：INIT 与 SUCCESS 两形态均按调用方装配持久化，超宽回执摘要钳 512 字符")
    void recordPersistsInitThenSuccessWithDigest() {
        // INIT 形态：P5 真实通道两段式先行落库（回执摘要预置 600 字符超 V604 VARCHAR(512) 列宽）
        InsuranceCallLog init = row(InsuranceCallStatus.INIT, "x".repeat(600));
        // SUCCESS 形态：PR-3 模拟通道即时返回直落（Task 12 preview 2102 留痕同形态）
        InsuranceCallLog success = row(InsuranceCallStatus.SUCCESS, "{\"totalAmount\":10000}");
        success.setCenterSerialNo("SIM-S100");
        success.setResultCode("0000");

        service.record(init);
        service.record(success);

        // 数据库写操作断言：两形态各落一行，摘要钳 512 恰为列宽、状态与中心回执字段逐项保真
        ArgumentCaptor<InsuranceCallLog> captor = ArgumentCaptor.forClass(InsuranceCallLog.class);
        verify(callLogMapper, times(2)).insert(captor.capture());
        InsuranceCallLog first = captor.getAllValues().get(0);
        assertThat(first.getStatus()).isEqualTo(InsuranceCallStatus.INIT);
        assertThat(first.getResponseDigest()).hasSize(512); // 超宽摘要入库前钳断
        InsuranceCallLog second = captor.getAllValues().get(1);
        assertThat(second.getStatus()).isEqualTo(InsuranceCallStatus.SUCCESS);
        assertThat(second.getResponseDigest()).isEqualTo("{\"totalAmount\":10000}"); // 未超宽原样
        assertThat(second.getCenterSerialNo()).isEqualTo("SIM-S100");
        assertThat(second.getResultCode()).isEqualTo("0000");
    }

    @Test
    @DisplayName("补偿重试状态守卫：SUCCESS 行拒重试 BILL-1026（非 TIMEOUT/FAILED 不进补偿）")
    void compensateRejectsNonTimeoutStateAsBill1026() {
        InsuranceCallLog success = row(InsuranceCallStatus.SUCCESS, "{}");
        success.setId(1L);
        when(callLogMapper.selectById(1L)).thenReturn(success);

        assertThatThrownBy(() -> service.compensate(1L, "重试"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.INSURANCE_COMPENSATE_STATE_NOT_ALLOWED));
        verify(callLogMapper, never()).updateById(any(InsuranceCallLog.class));
    }

    @Test
    @DisplayName("补偿缺行：id 无命中 BILL-1025 404（缺行守卫先于状态守卫）")
    void compensateRejectsMissingRowAsBill1025() {
        when(callLogMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.compensate(404L, "重试"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.INSURANCE_CALL_NOT_FOUND));
        verify(callLogMapper, never()).updateById(any(InsuranceCallLog.class));
    }

    @Test
    @DisplayName("补偿重试成功分支：FAILED 行迁 COMPENSATED 并回填结论留痕，恰一次落库")
    void compensateMarksFailedRowAsCompensatedWithNote() {
        // BILL-1026 守卫允许 TIMEOUT/FAILED 两态重试，本例取 FAILED 态行（TIMEOUT 同分支语义）
        InsuranceCallLog failed = row(InsuranceCallStatus.FAILED, null);
        failed.setId(2L);
        when(callLogMapper.selectById(2L)).thenReturn(failed);

        service.compensate(2L, "悬挂冲正完成，基金拆分已回退");

        // 数据库写操作断言：状态迁移 COMPENSATED + 结论留痕回填，恰一次落库
        ArgumentCaptor<InsuranceCallLog> captor = ArgumentCaptor.forClass(InsuranceCallLog.class);
        verify(callLogMapper, times(1)).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(InsuranceCallStatus.COMPENSATED);
        assertThat(captor.getValue().getCompensateNote()).isEqualTo("悬挂冲正完成，基金拆分已回退");
    }

    @Test
    @DisplayName("留痕分页查询：visitId 等值过滤 + id 升序稳定排序（wrapper 守卫钉死）")
    void pageFiltersByVisitIdAndOrdersByIdAsc() {
        InsuranceCallLog row = row(InsuranceCallStatus.SUCCESS, "{}");
        row.setId(3L);
        when(callLogMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<InsuranceCallLog> page = inv.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1);
            return page;
        });

        PageResult<InsuranceCallLog> result = service.page(VISIT, 0, 20);

        assertThat(result.page()).isZero(); // 0 基分页契约原样回显
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        // 查询 SQL 守卫钉死：等值条件落在 visit_id 列且携带请求就诊号、排序为 id 升序（A.4.3-17）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<InsuranceCallLog>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(callLogMapper).selectPage(any(), wrapperCaptor.capture());
        LambdaQueryWrapper<InsuranceCallLog> wrapper = (LambdaQueryWrapper<InsuranceCallLog>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("visit_id").containsIgnoringCase("ORDER BY");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(VISIT);
    }
}
