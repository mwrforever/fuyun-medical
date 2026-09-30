package com.fuyun.ward.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.ward.api.WardErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 冷链记录类型枚举（cold_chain_record.record_type 三值词表，V1101 列注释冻结）：
 * ALARM_HANDLE 类型登记完成即发布 ward.cold-chain.alert-archived（V1102 id 82）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + fromCode。
 */
public enum ColdChainRecordType {

    /** 巡检记录（合规基线：每日≥2 次、间隔≥6h，读时惰性判定 overdue） */
    INSPECTION("INSPECTION"),

    /** 告警处置记录（必填 alarm_ref + second_operator 双人核对；登记完成发布归档事件） */
    ALARM_HANDLE("ALARM_HANDLE"),

    /** 偏差记录（温度越限/设备故障等偏离登记） */
    DEVIATION("DEVIATION");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用的业务 code */
    @EnumValue
    private final String code;

    ColdChainRecordType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 INSPECTION），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或请求体文本；非空
     * @return 对应枚举常量，非空
     * @throws BizException WD-1007（400，词表外 code——脏数据或非法请求值）；EX-19 收口 A 类：
     *                      外部输入 code 转枚举失败按业务失败渲染，不再以裸 IAE 走 500 通道
     */
    public static ColdChainRecordType fromCode(String code) {
        for (ColdChainRecordType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new BizException(WardErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的冷链记录类型 code: " + code);
    }
}
