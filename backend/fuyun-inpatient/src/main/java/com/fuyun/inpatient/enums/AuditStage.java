package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 医嘱审核阶段两值（V905 order_audit.stage 值域，FU-M04-05 审核链口径）：SYSTEM 系统自动
 * 审核（开立后全员必经——非用药类直接过审迁移 AUDITED，用药类预检通过后停留 CREATED 待
 * 药师审）与 PHARMACIST 药师审方（M06 回执驱动，仅用药类 DRUG/DISCHARGE_MED）。审核链
 * 阶段判定统一经本枚举，禁散落字符串比较；stage 亦作 inpatient.order.audited 载荷
 * auditType 值（V800 id 41 契约：SYSTEM/PHARMACIST 双阶段口径）。
 */
public enum AuditStage {

    /** 系统自动审核（开立后全员必经预检；非用药类过审、用药类落预进行待药师审） */
    SYSTEM("SYSTEM"),

    /** 药师审方（M06 审方回执驱动；仅用药类医嘱） */
    PHARMACIST("PHARMACIST");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    AuditStage(String code) {
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
}
