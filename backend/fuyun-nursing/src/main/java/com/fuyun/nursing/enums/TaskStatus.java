package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理任务状态四值（V805 nursing_task.status 值域，Spec :127 任务状态机）：
 * {@code PENDING → IN_PROGRESS → COMPLETED} 为主迁移对，任一在途态可经取消 CAS 迁入
 * {@code CANCELLED}（取消原因强制留痕）。COMPLETED/CANCELLED 为终态（零迁出）。
 *
 * <p><b>声明态注记（PR-5 先例口径）</b>：{@link #IN_PROGRESS} 为状态机合法值但 <b>P1 无迁移入口</b>
 * （认领/开始执行随 P2 任务工作台引入）——P1 仅注册状态机迁移对（PENDING→IN_PROGRESS→COMPLETED），
 * 不写任何无触发的处理逻辑。
 */
public enum TaskStatus {

    /** 待执行（任务创建默认态；plan_time 为逾期判定基准） */
    PENDING("PENDING"),

    /** 执行中——P1 声明态：仅注册状态机迁移对（PENDING→IN_PROGRESS→COMPLETED），不写任何无触发的处理逻辑（认领/开始执行随 P2 任务工作台） */
    IN_PROGRESS("IN_PROGRESS"),

    /** 已完成（终态；complete CAS 迁入，completed_at 随 CAS 盖章） */
    COMPLETED("COMPLETED"),

    /** 已取消（终态；cancel CAS 迁入，取消原因强制留痕并发布 task.completed 事件） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    TaskStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code，非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举查询侧（清单查询入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static TaskStatus fromCode(String code) {
        for (TaskStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
