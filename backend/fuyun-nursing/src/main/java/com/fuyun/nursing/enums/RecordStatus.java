package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理记录状态三值（V802 nursing_record.status 值域，Spec :113 状态机）：DRAFT 草稿 /
 * SUBMITTED 已提交锁定 / REVISED 修订件。单向流转：DRAFT →（submit CAS）→ SUBMITTED →
 * （revise 插新行）→ REVISED；提交后正文锁定（NS-1007），修订链经 revised_from 指向原号、
 * 原行保留 SUBMITTED 态不删（GC25 修订留痕原值可见红线）。
 */
public enum RecordStatus {

    /** 草稿（可编辑、可提交） */
    DRAFT("DRAFT"),

    /** 已提交锁定（正文不可改，修订走 revise 链） */
    SUBMITTED("SUBMITTED"),

    /** 修订件（revise 产生的新行；原行保持 SUBMITTED） */
    REVISED("REVISED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    RecordStatus(String code) {
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
