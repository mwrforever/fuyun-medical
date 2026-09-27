package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 联动动作类型枚举（iot.linkage_rule.action_type 列值域，FU-M14-10 五类动作）。
 *
 * <p>P2 PR-2 Task 9 动作语义（brief 冻结面）：NOTIFY=WS 告警主题重复强化（alarm 帧重推一次带
 * linkage 标记）；M01_NOTIFY=M01 通知中心缺位降级留痕+warn（GC17①）；CALL_TRANSFER=ward 呼叫
 * 域回接点（Task 12 落地前暂存 PENDING）；NURSING_TASK=M05 护理任务创建（PR-3 回接，暂存
 * PENDING）；WARD_BROADCAST=M16 病区播报（域缺位暂存 PENDING）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum LinkageActionType {

    /** WS 告警强提醒：告警主题重复强化推送（alarm 帧重推一次带 linkage 标记头） */
    NOTIFY("NOTIFY"),

    /** 经 M01 通知中心发通知（P2 降级面：M01 缺位留痕+warn，GC17①） */
    M01_NOTIFY("M01_NOTIFY"),

    /** 转发 M16 呼叫（ward 呼叫域回接点，Task 12 落地前暂存 PENDING） */
    CALL_TRANSFER("CALL_TRANSFER"),

    /** 创建 M05 护理任务（PR-3 回接，落地前暂存 PENDING） */
    NURSING_TASK("NURSING_TASK"),

    /** M16 病区播报（域缺位暂存 PENDING，回接方收口） */
    WARD_BROADCAST("WARD_BROADCAST");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    LinkageActionType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 NOTIFY），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或管理台请求体；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或非法请求值），
     *                                  建议调用方按校验失败/数据异常处置
     */
    public static LinkageActionType fromCode(String code) {
        for (LinkageActionType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的联动动作类型 code: " + code);
    }
}
