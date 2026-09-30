package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 收费项目类别（charge_item.item_class 列值域，M13 Spec §4）：物价项目七分类，
 * 计价规则 item_scope 按类别圈选。枚举规范同 FeeStatus。
 */
public enum ItemClass {

    /** 西药 */
    WEST_DRUG("WEST_DRUG"),

    /** 中药 */
    TRAD_DRUG("TRAD_DRUG"),

    /** 诊疗项目 */
    TREATMENT("TREATMENT"),

    /** 耗材 */
    CONSUMABLE("CONSUMABLE"),

    /** 床位费 */
    BED("BED"),

    /** 护理费 */
    NURSING("NURSING"),

    /** 其他 */
    OTHER("OTHER");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ItemClass(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 WEST_DRUG），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；非空
     * @return 对应枚举常量，非空
     * @throws BizException BILL-1034（400）：code 无对应枚举常量（外部输入/存储值词表外，
     *      显式拒禁静默兜底——BE-C3-05 收口，改抛前为裸 IllegalArgumentException 出 500）
     */
    public static ItemClass fromCode(String code) {
        for (ItemClass itemClass : values()) {
            if (itemClass.code.equals(code)) {
                return itemClass;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的收费项目类别 code: " + code);
    }
}
