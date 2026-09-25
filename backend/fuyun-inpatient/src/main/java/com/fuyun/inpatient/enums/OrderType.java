package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 医嘱类型九值（V904 medical_order.order_type 值域，04-inpatient Spec §4 冻结）：每类携
 * routing 子键小写映射（InpatientMessagingConstants.withTypeKey 消费——order.created/
 * order.audited 事件按子键分发，drug 子键驱动 M06 审方任务生成、discharge-med 子键驱动
 * 出院带药计价）。DRUG/DISCHARGE_MED 两类用药面在 Task 6 审核链区分（用药类停留 CREATED
 * 待药师审，非用药类系统自动过审），判等口径统一经本枚举 isMedication()。
 */
public enum OrderType {

    /** 药品（用药类——drug 子键路由至 M06 审方；审核链停留 CREATED 待药师审） */
    DRUG("DRUG", "drug"),

    /** 检验（lab 子键——LIS 执行归口） */
    LAB("LAB", "lab"),

    /** 检查（exam 子键——PACS 执行归口） */
    EXAM("EXAM", "exam"),

    /** 手术（surgery 子键——M07 手术排程消费） */
    SURGERY("SURGERY", "surgery"),

    /** 用血（blood 子键——M08 输血闭环消费） */
    BLOOD("BLOOD", "blood"),

    /** 护理（nursing 子键——不经 order.audited 子键分发，经转抄链进 M05） */
    NURSING("NURSING", "nursing"),

    /** 膳食（diet 子键——营养膳食执行归口） */
    DIET("DIET", "diet"),

    /** 会诊（consult 子键——M04 会诊管理域消费） */
    CONSULT("CONSULT", "consult"),

    /** 出院带药（discharge-med 子键——用药类，审核链同 DRUG 停留待药师审） */
    DISCHARGE_MED("DISCHARGE_MED", "discharge-med");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    /** routing 子键（小写，事件路由键拼接第二段） */
    private final String subKey;

    OrderType(String code, String subKey) {
        this.code = code;
        this.subKey = subKey;
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
     * 取 routing 子键。
     *
     * @return 子键小写形态（如 drug/discharge-med），非空；来源：order.created/audited 事件路由
     */
    public String subKey() {
        return subKey;
    }

    /**
     * 是否用药类（审核链口径：DRUG 与 DISCHARGE_MED 停留 CREATED 待药师审，其余系统自动过审）。
     *
     * @return true=用药类医嘱
     */
    public boolean isMedication() {
        return this == DRUG || this == DISCHARGE_MED;
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（脏数据防御交调用方）
     */
    public static OrderType fromCode(String code) {
        for (OrderType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
