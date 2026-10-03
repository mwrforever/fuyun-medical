package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 发药单状态机九值（Spec :134 逐字冻结 + P2 PR-3 V1110 住院链增补）：共享前缀
 * CREATED(放行入队)→PICKING(批次锁定+追溯码采集)→PICKED(已配待核对)后分链——
 * 门诊链 PICKED→ISSUED(发药签名完成，发 dispense.completed，终态基点)→PART/FULL_RETURNED，
 * 门诊链止于 ISSUED 不受住院增补影响；住院链 PICKED→CHECKED(药师双人核对)→DELIVERED
 * (病区签收，住院终态——语义与门诊 ISSUED 区分：住院发药止于 DELIVERED 签收)；
 * CREATED/PICKING→CANCELLED（处方作废联动释放锁定批次）。
 */
public enum DispenseStatus {

    /** 放行入队 */
    CREATED("CREATED"),

    /** 配药中 */
    PICKING("PICKING"),

    /** 已配待核对 */
    PICKED("PICKED"),

    /** 已核对（住院链：摆药完成经药师双人核对，下一步病区签收） */
    CHECKED("CHECKED"),

    /** 已签收（住院链终态：病区护士签收交付，语义对齐门诊 ISSUED 基点） */
    DELIVERED("DELIVERED"),

    /** 已发药（门诊链终态基点） */
    ISSUED("ISSUED"),

    /** 部分退药 */
    PART_RETURNED("PART_RETURNED"),

    /** 全额退药 */
    FULL_RETURNED("FULL_RETURNED"),

    /**
     * 已取消（释放锁定批次）。备案：PR-4 无全单 CANCELLED 写入路径（发药中明细退场仅置明细
     * itemStatus=CANCELLED），完整作废联动 PR-5 回切（Spec 06-pharmacy §7 注记⑥）。
     */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    DispenseStatus(String code) {
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
     * @throws BizException PH-1022（400）code 无对应枚举（值域外 code 显式拒绝——EX-19 BE-C3-05 A 类收口）
     */
    public static DispenseStatus fromCode(String code) {
        for (DispenseStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new BizException(PharmacyErrorCode.ENUM_CODE_MALFORMED, HttpStatus.BAD_REQUEST, "未知的发药单状态 code: " + code);
    }
}
