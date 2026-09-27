package com.fuyun.ward.dto;

import com.fuyun.ward.enums.ColdChainPurpose;
import com.fuyun.ward.enums.TempRangeType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * 冷链档案保存请求（POST/PUT /api/v1/ward/cold-chain/archives 请求体，建档与更新共用）。
 *
 * @param purpose         用途，非空；来源：请求体
 * @param deviceId        监测设备号，非空；来源：请求体
 * @param tempRangeType   温度区间类型，非空；来源：请求体
 * @param verifyDueAt     校验/验证到期时刻，可空；来源：请求体
 * @param inventoryDigest 存量清单摘要，可空；来源：请求体
 */
public record SaveColdChainArchiveRequest(
        @NotNull(message = "purpose 不能为空") ColdChainPurpose purpose,

        @NotNull(message = "deviceId 不能为空") @Size(max = 64, message = "deviceId 最长 64 字符")
        String deviceId,

        @NotNull(message = "tempRangeType 不能为空") TempRangeType tempRangeType,
        OffsetDateTime verifyDueAt,

        @Size(max = 500, message = "inventoryDigest 最长 500 字符")
        String inventoryDigest) {}
