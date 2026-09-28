package com.fuyun.billing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结算/退费并发收口原子语句 SQL 守卫测试（2026-09-18 用户裁决修复轮；Task 8 wrapper SQL
 * 守卫先例的注解 SQL 同型形态）：结算/退费执行侧三支 mapper 方法承载资金并发语义（CAS 谓词/
 * 行锁子句/锁序），退费审批侧三支 CAS 条件更新承载分权与账实并发语义（BUG-10），语句子句一旦
 * 漂移即并发缺口复发——测试逐子句钉死，防后人「顺手优化」拆掉守卫。
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

    @Test
    @DisplayName("一级审批 CAS 守卫：casEscalateFirstApproval 谓词必须钉死 PENDING_APPROVAL 单态前置+审批链同 SET（BUG-10）")
    void casEscalateFirstApprovalSqlPinsSinglePendingPredicateAndChainColumns() throws Exception {
        String sql = sqlOf(RefundRequestMapper.class, "casEscalateFirstApproval");
        assertThat(sql).contains("WHERE id = #{id}");
        // 单态谓词禁放宽 IN 双态：两一级审批人并发批同一单，后提交者据此 0 行落败、不得覆盖 firstApprover
        assertThat(sql).contains("status = 'PENDING_APPROVAL'");
        assertThat(sql).contains("SET status = 'PENDING_SECOND_APPROVAL'");
        // 一级审批链两列随状态迁移同语句原子落库（拆两条语句即留「升批已落而审批链未留痕」中间态）
        assertThat(sql).contains("first_approver = #{firstApprover}");
        assertThat(sql).contains("first_approved_at = #{firstApprovedAt}");
        // 逻辑删守卫显式补齐（注解 SQL 不继承 @TableLogic，缺即误改已删行）
        assertThat(sql).contains("deleted = 0");
    }

    @Test
    @DisplayName("终批 CAS 守卫：casFinalApprove 谓词必须钉死读快照精确旧态参数+终批两列同 SET（BUG-10）")
    void casFinalApproveSqlPinsExactFromStatusPredicateAndFinalColumns() throws Exception {
        String sql = sqlOf(RefundRequestMapper.class, "casFinalApprove");
        assertThat(sql).contains("WHERE id = #{id}");
        // 精确旧态参数谓词（调用方传读快照 status code，随分支钉死）：禁放宽 IN 双态——L1 终批入口
        //   借宽谓词可在并发一级升批后跳级终批，连批守卫基于读快照评估即被绕过（BILL-1020 并发面）
        assertThat(sql).contains("status = #{fromStatus}");
        assertThat(sql).contains("SET status = 'APPROVED'");
        assertThat(sql).contains("approver = #{approver}");
        assertThat(sql).contains("approved_at = #{approvedAt}");
        assertThat(sql).contains("deleted = 0");
    }

    @Test
    @DisplayName("驳回 CAS 守卫：casReject 谓词必须锁死待审双态前置+驳回理由同 SET（已执行单 0 行拦下，BUG-10）")
    void casRejectSqlPinsPendingPredicateAndReasonColumn() throws Exception {
        String sql = sqlOf(RefundRequestMapper.class, "casReject");
        assertThat(sql).contains("WHERE id = #{id}");
        // 待审双态均可驳回（二级驳回=一级已批后否决整单）；已 APPROVED/EXECUTED 单被谓词排除，
        //   并发交错（他终批/执行先落）0 行命中——动卡退钱完成的单禁覆写回 REJECTED（账实一致）
        assertThat(sql).contains("status IN ('PENDING_APPROVAL', 'PENDING_SECOND_APPROVAL')");
        assertThat(sql).contains("SET status = 'REJECTED'");
        assertThat(sql).contains("reject_reason = #{reason}");
        assertThat(sql).contains("deleted = 0");
    }
}
