package com.fuyun.outpatient.service;

import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * visit 主状态机单点校验（03 Spec 红线 5「全部迁移经状态机单点校验+visit_status_log 每迁必记」）：
 * 合法迁移对全集九对静态表（REGISTERED→WAITING、WAITING→IN_CONSULT、IN_CONSULT→PENDING_FEE、
 * PENDING_FEE→IN_CONSULT、REGISTERED→CANCELLED、WAITING→CANCELLED、IN_CONSULT→FINISHED、
 * PENDING_FEE→FINISHED、REGISTERED→NO_SHOW〔声明态〕）。全部迁移点（报到/接诊/诊毕/退号回执/
 * 待缴费推进）迁移前必须经 {@link #require(String, String)} 校验，CAS 命中后再写 visit_status_log。
 * 终态（FINISHED/CANCELLED）不在任何迁移对迁出侧——终态后一切开单/缴费/执行动作天然被拒（OP-1011）。
 * 线程安全：无状态静态工具（不可变集合）。
 */
public final class OutpatientVisitStateMachine {

    /** 迁移对拼接符（from->to 静态表键形态，字面量与 Global Constraints 全集逐字同源） */
    private static final String ARROW = "->";

    /** 合法迁移对全集（红线 5 九对；NO_SHOW 为声明态迁移对，P3 爽约判定任务触发） */
    private static final Set<String> LEGAL_TRANSITIONS = Set.of(
            "REGISTERED->WAITING",
            "WAITING->IN_CONSULT",
            "IN_CONSULT->PENDING_FEE",
            "PENDING_FEE->IN_CONSULT",
            "REGISTERED->CANCELLED",
            "WAITING->CANCELLED",
            "IN_CONSULT->FINISHED",
            "PENDING_FEE->FINISHED",
            "REGISTERED->NO_SHOW");

    /** 纯静态工具类，禁止实例化 */
    private OutpatientVisitStateMachine() {}

    /**
     * 迁移合法性校验（迁移点 CAS 前置单点校验）：迁移对不在全集即抛 OP-1011（409）。
     *
     * @param from 迁出态 code（VisitStatus.getCode()，如 WAITING）；来源：visit 行当前态
     * @param to   迁入态 code（VisitStatus.getCode()，如 IN_CONSULT）；来源：业务动作目标态
     * @throws BizException OP-1011 VISIT_STATE_NOT_ALLOWED（409，非法迁移对——含终态迁出与声明态
     *                      越迁）时触发；建议处理策略：调用方直接上抛经全局渲染器出 ProblemDetail
     */
    public static void require(String from, String to) {
        if (!LEGAL_TRANSITIONS.contains(from + ARROW + to)) {
            throw new BizException(
                    OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "就诊状态迁移不合法（" + from + " → " + to + "，红线 5 状态机违例）");
        }
    }
}
