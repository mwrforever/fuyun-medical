package com.fuyun.billing.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 22 枚举全量契约测试（A.2-7 code↔enum 双向映射统一模板验证）：每个枚举常量
 * code 与枚举名一致（V600–V604 列注释直读口径）、fromCode/getCode 双向闭环、
 * values/valueOf 生成方法同源可用、值域外 code 显式拒绝（脏数据禁静默）。
 * 值集全序锚定（状态机敏感的 FeeStatus/SettlementStatus/TriggerType）归
 * {@link BillingEnumsContractTest}，本测试不重复钉序只验映射模板。
 */
class BillingEnumsFullContractTest {

    /** 22 枚举全集：新增枚举必须登记入列（漏登即本测试不再覆盖其映射契约，CI 兜底） */
    private static final List<Class<? extends Enum<?>>> BILLING_ENUMS = List.of(
            ChargeSource.class,
            DepositStatus.class,
            DepositTxnStatus.class,
            DepositTxnType.class,
            ExecOccupyStatus.class,
            FeeStatus.class,
            InsuranceCallStatus.class,
            InsurancePayType.class,
            ItemClass.class,
            ItemPriceFlag.class,
            ItemStatus.class,
            MapType.class,
            MappingStatus.class,
            PayerType.class,
            PaymentMethod.class,
            PriceSource.class,
            PriceStatus.class,
            RefundStatus.class,
            RefundType.class,
            SettlementStatus.class,
            TriggerType.class,
            VisitType.class);

    @Test
    @DisplayName("22 枚举契约：code=常量名 + fromCode 双向闭环 + valueOf 同源 + 值域外拒绝")
    void allBillingEnumsFollowCodeRoundTripContract() throws Exception {
        assertThat(BILLING_ENUMS).hasSize(22);

        for (Class<? extends Enum<?>> type : BILLING_ENUMS) {
            Method getCode = type.getMethod("getCode");
            Method fromCode = type.getMethod("fromCode", String.class);
            Method values = type.getMethod("values");
            Method valueOf = type.getMethod("valueOf", String.class);

            Object[] constants = (Object[]) values.invoke(null);
            assertThat(constants).as("%s 常量集非空", type.getSimpleName()).isNotEmpty();

            for (Object constant : constants) {
                String code = (String) getCode.invoke(constant);
                // 列值直读口径：code 与枚举常量名一致，DB 列无第二套词表
                assertThat(code).isEqualTo(((Enum<?>) constant).name());
                assertThat(fromCode.invoke(null, code)).isSameAs(constant);
                assertThat(valueOf.invoke(null, ((Enum<?>) constant).name())).isSameAs(constant);
            }

            // 值域外拒绝：fromCode/valueOf 均抛 IllegalArgumentException（反射调用统一包装为 InvocationTargetException）
            assertThatThrownBy(() -> fromCode.invoke(null, "__UNKNOWN__"))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> valueOf.invoke(null, "__UNKNOWN__"))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
