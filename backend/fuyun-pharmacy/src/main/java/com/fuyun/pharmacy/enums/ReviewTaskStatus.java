package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 住院用药审方任务状态三值（V1000 review_task.status 列注释冻结）：PENDING（消费 drug 子键
 * 事件落库初态）→APPROVED（审方通过，发布 audit-completed 回执）/REJECTED（审方驳回[意见
 * 必附]，发布 audit-rejected 回执，M04 医生站重提路径）；重提重发事件对 REJECTED 任务复位
 * 重开（同任务非新建）。终态无出边（APPROVED 不可再决）。
 */
public enum ReviewTaskStatus {

    /** 待审（工作台可决） */
    PENDING("PENDING"),

    /** 审方通过（终态；回执 audit-completed 已发布） */
    APPROVED("APPROVED"),

    /** 审方驳回（重提路径触发同任务复位重开） */
    REJECTED("REJECTED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    ReviewTaskStatus(String code) {
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
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举（脏数据）
     */
    public static ReviewTaskStatus fromCode(String code) {
        for (ReviewTaskStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的审方任务状态 code: " + code);
    }
}
