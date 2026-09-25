package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会诊单状态四值（V908 consultation.status 值域，04-inpatient Spec §5 会诊状态机冻结）：
 * REQUESTED 已申请待响应 → ACCEPTED 受邀科已接单 → COMPLETED 会诊完成（终态，意见归档）；
 * REQUESTED/ACCEPTED → CANCELLED 取消（终态）。<b>逾期升级不在状态机内</b>——超时为动作
 * 广播（inpatient.consultation.overdue，overdue_flag 承载），状态停留 REQUESTED 仍可被响应
 * （Spec FU-M04-09 冻结口径）；状态迁移唯一经 ConsultationMapper CAS 条件更新 + 影响行数
 * 判定（GC23——会诊为模块内独立小状态机，不经 OrderStateMachineService）。
 */
public enum ConsultationStatus {

    /** 已申请待响应（逾期升级动作面停留态；接单/取消两合法出边） */
    REQUESTED("REQUESTED"),

    /** 受邀科已接单（response_time 落值；意见提交/取消两合法出边） */
    ACCEPTED("ACCEPTED"),

    /** 会诊完成（终态；consult_time 与意见落值，归档供 M09 引用） */
    COMPLETED("COMPLETED"),

    /** 已取消（终态；申请方撤单，REQUESTED/ACCEPTED 两态可达） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    ConsultationStatus(String code) {
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
     * @return 对应枚举常量，非空；无匹配返回 null（脏数据防御交调用方）
     */
    public static ConsultationStatus fromCode(String code) {
        for (ConsultationStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
