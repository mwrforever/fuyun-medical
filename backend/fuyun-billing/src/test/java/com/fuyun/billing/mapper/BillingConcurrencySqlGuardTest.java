package com.fuyun.billing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结算/退费并发收口原子语句 SQL 守卫测试（2026-09-18 用户裁决修复轮；Task 8 wrapper SQL
 * 守卫先例的注解 SQL 同型形态）：三支 mapper 方法承载资金并发语义（CAS 谓词/行锁子句/锁序），
 * 语句子句一旦漂移即并发缺口复发——测试逐子句钉死，防后人「顺手优化」拆掉守卫。
 */
class BillingConcurrencySqlGuardTest {

    /** 取 mapper 方法上的注解 SQL 文本（@Update/@Select 任一承载）。 */
    private static String sqlOf(Class<?> mapper, String methodName) throws NoSuchMethodException {
        Method method = mapper.getMethod(methodName, methodParamTypes(mapper, methodName));
        Update update = method.getAnnotation(Update.class);
        if (update != null) {
            return String.join(" ", update.value());
        }
        Select select = method.getAnnotation(Select.class);
        if (select != null) {
            return String.join(" ", select.value());
        }
        throw new IllegalStateException("方法未承载注解 SQL：" + mapper.getSimpleName() + "." + methodName);
    }

    /** 按方法名反射取参类型（三方法参数均为原生 long/String/List 组合）。 */
    private static Class<?>[] methodParamTypes(Class<?> mapper, String methodName) throws NoSuchMethodException {
        for (Method m : mapper.getMethods()) {
            if (m.getName().equals(methodName)) {
                return m.getParameterTypes();
            }
        }
        throw new NoSuchMethodException(mapper.getSimpleName() + "." + methodName);
    }

    @Test
    @DisplayName("结算锚 CAS 守卫：casMarkSettled 谓词必须锁死 id+DRAFT/PRESETTLED 双态前置，终态三字段同语句")
    void casMarkSettledSqlPinsStatusPredicateAndTerminalColumns() throws Exception {
        String sql = sqlOf(SettlementMapper.class, "casMarkSettled");
        // 状态条件更新谓词：仅可结算态允许迁移（并发赢家提交后输家条件不命中=幂等分流的前提）
        assertThat(sql).contains("WHERE id = #{id}");
        assertThat(sql).contains("status IN ('DRAFT', 'PRESETTLED')");
        // 终态字段同语句落库：状态迁移+结算时刻+支付明细一次性原子完成（禁拆两条语句留中间态）
        assertThat(sql).contains("SET status = 'SETTLED'");
        assertThat(sql).contains("settled_at = #{settledAt}");
        assertThat(sql).contains("payment_details = #{paymentDetails}");
        // 逻辑删守卫显式补齐（注解 SQL 不继承 @TableLogic，缺即误改已删行）
        assertThat(sql).contains("deleted = 0");
    }

    @Test
    @DisplayName("费用行条件更新守卫：casMarkFeesSettled 谓词必须锁死 PENDING 前置+结算引用回填+逻辑删")
    void casMarkFeesSettledSqlPinsPendingPredicateAndSettlementBackfill() throws Exception {
        String sql = sqlOf(FeeRecordMapper.class, "casMarkFeesSettled");
        // 仅 PENDING 行可迁移：双单并发抢同批费用时后到者命中数不足即整体回滚（双扣防线）
        assertThat(sql).contains("status = 'PENDING'");
        assertThat(sql).contains("settlement_id = #{settlementId}");
        assertThat(sql).contains("deleted = 0");
        assertThat(sql).contains("<foreach");
        assertThat(sql).contains("id IN");
    }

    @Test
    @DisplayName("退费行锁守卫：lockByIds 必须携带 FOR UPDATE 行锁子句+id 升序锁序（防多行交叉加锁死锁）")
    void lockByIdsSqlPinsForUpdateAndOrderedLocking() throws Exception {
        String sql = sqlOf(FeeRecordMapper.class, "lockByIds");
        // 行锁子句：无 FOR UPDATE 即退化为普通读，并发 apply 守卫互不可见 TOCTOU 复发
        assertThat(sql).contains("FOR UPDATE");
        // 锁序钉死 id 升序：并发多行申请按同一顺序加锁，杜绝交叉持锁死锁
        assertThat(sql).contains("ORDER BY id");
        assertThat(sql).contains("deleted = 0");
        assertThat(sql).contains("<foreach");
    }
}
