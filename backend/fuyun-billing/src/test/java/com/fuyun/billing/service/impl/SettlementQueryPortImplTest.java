package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.entity.Settlement;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.mapper.SettlementMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 结算单反查端口转调单测（Task 10；父 POM 规则二 com.fuyun.billing.service.impl 包 LINE=1.00
 * 行覆盖承载）：按计费点分组去重升序反查（单据精确放行/回滚清单的输入侧）与放行凭证核验两路
 * （true=SETTLED 行命中 / false=结算单缺失或无命中）。端口为跨模块只读转调面，mock mapper 验证
 * 投影与语义收敛，不触库。
 */
@ExtendWith(MockitoExtension.class)
class SettlementQueryPortImplTest {

    @Mock
    private FeeRecordMapper feeRecordMapper;

    @Mock
    private SettlementMapper settlementMapper;

    private SettlementQueryPortImpl port;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次：费用行/结算单两实体）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Settlement.class);
    }

    @BeforeEach
    void setUp() {
        port = new SettlementQueryPortImpl(feeRecordMapper, settlementMapper);
    }

    @Test
    @DisplayName("sourceRefsOfSettlement：混合 trigger 费用行按计费点分组——orderRefs 去重升序、rxRefs 独立成组、MANUAL 不进投影")
    void sourceRefsGroupsByTriggerPointSortedDeduped() {
        // 数据库读替身：同结算单下混合计费点行（ORDER_CONFIRMED 乱序+重复、PRESCRIPTION_EFFECTIVE、MANUAL 挂号费）
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(
                        feeRow("OP20260920000002", TriggerType.ORDER_CONFIRMED),
                        feeRow("OP20260920000001", TriggerType.ORDER_CONFIRMED),
                        feeRow("OP20260920000001", TriggerType.ORDER_CONFIRMED),
                        feeRow("RX20260920000001", TriggerType.PRESCRIPTION_EFFECTIVE),
                        feeRow("501", TriggerType.MANUAL)));

        var refs = port.sourceRefsOfSettlement(501L);

        // 单据精确清单：去重升序（另序入列收敛为唯一序）；处方组独立；手工计费点（挂号费）不进投影
        assertThat(refs.settlementId()).isEqualTo(501L);
        assertThat(refs.orderRefs()).containsExactly("OP20260920000001", "OP20260920000002");
        assertThat(refs.rxRefs()).containsExactly("RX20260920000001");
    }

    @Test
    @DisplayName("settledUnder：结算单下命中该来源单据的 SETTLED 费用行——true（Task 11 verify 凭证有效输入侧）")
    void settledUnderTrueWhenSettledFeeRowExists() {
        Settlement settlement = new Settlement();
        settlement.setId(501L);
        when(settlementMapper.selectOne(any())).thenReturn(settlement);
        when(feeRecordMapper.selectCount(any())).thenReturn(1L);

        assertThat(port.settledUnder("S20260920001", "OP20260920000001")).isTrue();
    }

    @Test
    @DisplayName("settledUnder：结算单缺失或费用行无 SETTLED 命中——false（凭证核验拒绝分支输入侧）")
    void settledUnderFalseWhenNoMatchingRow() {
        // 无命中路径一：结算编号定位不到结算单（凭证无效短路）
        when(settlementMapper.selectOne(any())).thenReturn(null);
        assertThat(port.settledUnder("S20260920MISS", "OP20260920000001")).isFalse();

        // 无命中路径二：结算单在但该单据无 SETTLED 行（未结算/已退费）
        Settlement settlement = new Settlement();
        settlement.setId(502L);
        when(settlementMapper.selectOne(any())).thenReturn(settlement);
        when(feeRecordMapper.selectCount(any())).thenReturn(0L);
        assertThat(port.settledUnder("S20260920002", "RX20260920000001")).isFalse();
    }

    /**
     * 费用行替身（settlementId 统一 501，仅 source_ref/trigger_point 参与断言）。
     *
     * @param sourceRef 来源单据引用
     * @param trigger   计费点
     * @return 费用行替身，非空
     */
    private static FeeRecord feeRow(String sourceRef, TriggerType trigger) {
        FeeRecord row = new FeeRecord();
        row.setSettlementId(501L);
        row.setSourceRef(sourceRef);
        row.setTriggerPoint(trigger);
        return row;
    }
}
