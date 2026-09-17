package com.fuyun.patient.vo;

import java.time.OffsetDateTime;

/**
 * 就诊卡出参：介质本体为标识行，标识值禁出。
 *
 * @param identifierId 标识行 id（介质本体主键）
 * @param patientId    挂接的主索引
 * @param cardNo       卡面号
 * @param status       状态：ACTIVE/LOST/REPLACED/DISABLED
 * @param boundAt      绑定时刻
 * @param unboundAt    解绑/失效时刻（可空）
 */
public record CardVO(
        Long identifierId,
        Long patientId,
        String cardNo,
        String status,
        OffsetDateTime boundAt,
        OffsetDateTime unboundAt) {}
