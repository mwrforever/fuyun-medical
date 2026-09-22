package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理任务优先级三值（V805 nursing_task.priority 值域）：HIGH 高 / NORMAL 普通（缺省）/ LOW 低。
 * 任务列表按优先级分组展示（任务工作台归 P2）；P1 仅随创建落库，无独立调度语义。
 */
public enum TaskPriority {

    /** 高优先级（如评估高危联动防范任务，Task 8 固定落 HIGH） */
    HIGH("HIGH"),

    /** 普通优先级（创建缺省值，与 V805 列默认同源） */
    NORMAL("NORMAL"),

    /** 低优先级（可延后执行的非紧急任务） */
    LOW("LOW");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    TaskPriority(String code) {
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
     * code → 枚举查询侧（创建入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static TaskPriority fromCode(String code) {
        for (TaskPriority value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
