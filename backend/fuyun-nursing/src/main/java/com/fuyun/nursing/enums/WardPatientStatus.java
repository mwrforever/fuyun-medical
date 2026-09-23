package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 病区患者视图状态二值（V801 nursing_ward_patient.status 值域，GC38 语义锁冻结）：IN_WARD 在区 /
 * REMOVED 已移出病区一览。<b>故意不设</b> {@code TRANSFERRED_OUT}/DISCHARGED 等值——出院/转科/换床
 * 属 ADT 语义，权威在 M04 住院域（M05 对住院业务状态零权威）；本枚举仅表达「病区一览展示面」的
 * 在区/移出二态，任何扩充值域的诉求须经决策点重新评审（写路径下渗禁令的可执行锚之一）。
 */
public enum WardPatientStatus {

    /** 在区（病区一览可见；床位/就诊双唯一约束的作用态） */
    IN_WARD("IN_WARD"),

    /** 已移出病区一览（本地视图行退役标记，非 ADT 状态；不触发任何跨模块外发） */
    REMOVED("REMOVED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    WardPatientStatus(String code) {
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
    public static WardPatientStatus fromCode(String code) {
        for (WardPatientStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
