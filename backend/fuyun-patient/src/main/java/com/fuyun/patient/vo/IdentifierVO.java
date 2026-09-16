package com.fuyun.patient.vo;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 标识出参（GET /patients/{id}/identifiers 行载体）：标识值禁出（仅卡面号可读）。
 */
@Getter
@Setter
public class IdentifierVO {

    /** 标识行 id */
    private Long id;

    /** 标识类型（IdentifierType 词表） */
    private String identifierType;

    /** 卡面号（卡类介质；非卡为空） */
    private String cardNo;

    /** 状态：ACTIVE/LOST/REPLACED/DISABLED */
    private String status;

    /** 主标识标记 */
    private Boolean isPrimary;

    /** 绑定时刻 */
    private OffsetDateTime boundAt;

    /** 解绑/失效时刻（可空） */
    private OffsetDateTime unboundAt;
}
