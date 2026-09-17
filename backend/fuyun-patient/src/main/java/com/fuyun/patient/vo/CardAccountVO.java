package com.fuyun.patient.vo;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 一卡通账户出参（GET /card-accounts/{id} 与按患者查询回显载体）。
 */
@Getter
@Setter
public class CardAccountVO {

    /** 账户 id */
    private Long id;

    /** 患者主索引 */
    private Long patientId;

    /** 余额（分） */
    private Long balance;

    /** 状态机：ACTIVE/FROZEN/CLOSED */
    private String status;

    /** 开户时刻 */
    private OffsetDateTime openedAt;

    /** 销户时刻（CLOSED 后非空） */
    private OffsetDateTime closedAt;
}
