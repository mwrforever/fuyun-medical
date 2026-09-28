package com.fuyun.iot.vo;

/**
 * 二次确认凭证视图对象（confirm-challenge 端点出网载体）：凭证标识 + 预占命令号 + 有效期。
 * 凭证以 Redis GETDEL 一次性消费（TTL 120s），过期/已用/缺失下发均 IOT-1015。
 *
 * @param challengeId 凭证标识（UUID），非空；下发请求必携
 * @param commandNo   预占命令号（CMD{yyyyMMdd}{%05d}），非空；下发落行沿用，前端可先行展示
 * @param expiresIn   凭证有效期（秒，固定 120）；过期由 Redis TTL 兜底
 */
public record ConfirmChallengeVO(String challengeId, String commandNo, long expiresIn) {

    /** 凭证有效期秒数（brief 冻结值：TTL 120s） */
    public static final long EXPIRES_IN_SECONDS = 120L;
}
