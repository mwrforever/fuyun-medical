package com.fuyun.patient.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 一卡通余额原子记账 SQL 守卫测试（EX-23；fuyun-billing BillingConcurrencySqlGuardTest 注解
 * SQL 守卫同型形态）：recordBalance 承载「余额唯一写点」语义（审查 I5 串行化台账）——
 * 账户状态谓词、逻辑删过滤、RETURNING 原子回读任一漂移即记账守卫缺口（软删行被命中即
 * 死账户复活记账），测试逐子句钉死，防后人「顺手优化」拆掉守卫。
 */
class CardAccountSqlGuardTest {

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

    /** 按方法名反射取参类型。 */
    private static Class<?>[] methodParamTypes(Class<?> mapper, String methodName) throws NoSuchMethodException {
        for (Method m : mapper.getMethods()) {
            if (m.getName().equals(methodName)) {
                return m.getParameterTypes();
            }
        }
        throw new NoSuchMethodException(mapper.getSimpleName() + "." + methodName);
    }

    @Test
    @DisplayName("原子记账守卫：recordBalance 谓词必须锁死 id+ACTIVE 前置+逻辑删过滤，RETURNING 单语句回读（EX-23）")
    void recordBalanceSqlPinsStatusPredicateAndDeletedGuard() throws Exception {
        String sql = sqlOf(CardAccountMapper.class, "recordBalance");
        // 记账目标定位与账户状态谓词：非 ACTIVE 账户禁记账（软删行不得命中——注解 SQL 不继承 @TableLogic）
        assertThat(sql).contains("WHERE id = #{accountId}");
        assertThat(sql).contains("status = 'ACTIVE'");
        // 逻辑删守卫显式补齐（EX-23：软删账户被记账=死账户复活，与 billing DepositAccountMapper.mutateBalance 对齐）
        assertThat(sql).contains("AND deleted = 0");
        // 原子形态守卫：UPDATE 与余额回读单语句完成（拆两语句即重现并发窗口，审查 I5 根因）
        assertThat(sql).contains("RETURNING balance");
    }
}
