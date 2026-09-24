package com.fuyun.billing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 退费可退余额聚合下推 SQL 守卫测试（PERF-01；Task 8 wrapper SQL 守卫先例与
 * BillingConcurrencySqlGuardTest 注解 SQL 守卫同型的 XML 形态）：两支聚合 SQL 承载 W-17
 * 资金口径（已决=APPROVED/EXECUTED 全量、在途=PENDING_* 可排斥自身）——状态谓词、逻辑删过滤、
 * JOIN 勾稽键、在途排斥子句任一漂移即守卫口径漂移（费用行判态与超可退守卫直接消费本聚合值），
 * 测试逐子句钉死，防后人「顺手优化」拆掉资金语义。
 */
class RefundAggregateSqlGuardTest {

    /** 从主资源 classpath 读 XML 全文（main/resources 随 target/classes 进入测试类路径）。 */
    private static String xmlText() {
        try (InputStream in = RefundAggregateSqlGuardTest.class.getResourceAsStream("/mapper/RefundRequestMapper.xml")) {
            if (in == null) {
                throw new IllegalStateException("未找到聚合映射 XML：resources/mapper/RefundRequestMapper.xml");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取聚合映射 XML 失败", e);
        }
    }

    /** 截取指定 select 节点的 SQL 正文（按 id 定位到对应闭合标签，缺失即断言失败）。 */
    private static String selectBlock(String id) {
        String xml = xmlText();
        int start = xml.indexOf("<select id=\"" + id + "\"");
        int end = xml.indexOf("</select>", start);
        assertThat(start).as("聚合 select 节点必须存在：%s", id).isNotNegative();
        assertThat(end).as("聚合 select 节点必须闭合：%s", id).isNotNegative();
        return xml.substring(start, end);
    }

    @Test
    @DisplayName("已决聚合守卫：状态谓词锁死 APPROVED/EXECUTED 两态、在途两态绝不混入（execute 判态口径）")
    void decidedAggregateSqlPinsDecidedOnlyStatusPredicate() {
        String sql = selectBlock("sumDecidedRefundedFenByFeeIds");
        // 已决单值口径：漏任一态即少算已退（apply 守卫放水）；混入在途态即把可驳回额当已退（判态误标）
        assertThat(sql).contains("r.status IN ('APPROVED', 'EXECUTED')");
        assertThat(sql).doesNotContain("PENDING_APPROVAL", "PENDING_SECOND_APPROVAL");
    }

    @Test
    @DisplayName("在途聚合守卫：状态谓词锁死 PENDING_* 两态、excludeRefundId 排斥子句按需生效（W-17 排斥语义）")
    void inFlightAggregateSqlPinsInFlightStatusPredicateAndSelfExclusion() {
        String sql = selectBlock("sumInFlightRefundedFenByFeeIds");
        // 在途单值口径：已决两态混入即把不可逆额重复计入占用位
        assertThat(sql).contains("r.status IN ('PENDING_APPROVAL', 'PENDING_SECOND_APPROVAL')");
        assertThat(sql).doesNotContain("'APPROVED'", "'EXECUTED'");
        // 排斥子句：excludeRefundId 判空生效（null=不排斥），非空时 != 排除自身在途单
        assertThat(sql).contains("<if test=\"excludeRefundId != null\">");
        assertThat(sql).contains("r.id != #{excludeRefundId}");
    }

    @Test
    @DisplayName("聚合勾稽守卫：JOIN 主键勾稽 + 两表逻辑删显式过滤 + SUM 分组列与唯一行序（A.4.3-15/17）")
    void aggregateSqlPinsJoinDeletedGuardGroupByAndOrdering() {
        for (String id : new String[] {"sumDecidedRefundedFenByFeeIds", "sumInFlightRefundedFenByFeeIds"}) {
            String sql = selectBlock(id);
            // JOIN 勾稽键=退费单主键（错列即串账）；逻辑删两表均显式补齐（原生 SQL 不继承 @TableLogic）
            assertThat(sql).contains("JOIN billing.refund_request r ON r.id = l.refund_id");
            assertThat(sql).contains("l.deleted = 0");
            assertThat(sql).contains("r.deleted = 0");
            // 求和列与分组键：SUM(refund_amount) 按 fee_id 分组，行序唯一（A.4.3-17）
            assertThat(sql).contains("SUM(l.refund_amount)");
            assertThat(sql).contains("l.fee_id IN");
            assertThat(sql).contains("GROUP BY l.fee_id");
            assertThat(sql).contains("ORDER BY l.fee_id");
        }
    }
}
