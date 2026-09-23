package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 交接班状态三值（V807 nursing.shift_handover.status 值域，Spec :129 交接班状态机）：
 * {@code DRAFT → SIGNING → COMPLETED} 为主迁移对，complete CAS 可自 DRAFT 或 SIGNING 直达
 * {@code COMPLETED}（双签同刻记录：交班签名=生成时刻、接班签名=完成时刻）；COMPLETED 为终态
 * （零迁出），未完成不阻塞业务（任务/执行照常运行，无副作用）。
 *
 * <p><b>声明态注记（PR-5 先例口径）</b>：{@link #SIGNING} 为状态机合法值但 <b>P1 无独立迁移入口</b>
 * （签署中态随 P2 双人分步签署引入）——P1 仅注册状态机迁移对（DRAFT/SIGNING → COMPLETED），
 * 不写任何无触发的处理逻辑。
 */
public enum HandoverStatus {

    /** 草稿（系统按本班业务数据自动汇总生成的初始态；交班签名已随生成盖章） */
    DRAFT("DRAFT"),

    /** 签署中——P1 声明态：仅注册迁移对（DRAFT/SIGNING → COMPLETED），不写任何无触发的处理逻辑（分步双签随 P2 双人签署引入） */
    SIGNING("SIGNING"),

    /** 已完成（终态；complete CAS 迁入，接班签名同刻盖章并发布 nursing.shift.completed） */
    COMPLETED("COMPLETED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    HandoverStatus(String code) {
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
     * code → 枚举查询侧（入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static HandoverStatus fromCode(String code) {
        for (HandoverStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
