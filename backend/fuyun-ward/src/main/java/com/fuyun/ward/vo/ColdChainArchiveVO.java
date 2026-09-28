package com.fuyun.ward.vo;

import com.fuyun.ward.entity.ColdChainArchiveEntity;
import com.fuyun.ward.enums.ColdChainPurpose;
import com.fuyun.ward.enums.TempRangeType;
import java.time.OffsetDateTime;

/**
 * 冷链档案视图对象（冷链域出网载体）：档案全字段 + 巡检逾期注记（读时惰性判定结果，不落库）。
 *
 * @param id              档案行雪花 id，非空
 * @param archiveNo       档案业务号，非空
 * @param purpose         用途，非空
 * @param deviceId        监测设备号，非空
 * @param tempRangeType   温度区间类型，非空
 * @param verifyDueAt     校验/验证到期时刻，可空
 * @param inventoryDigest 存量清单摘要，可空
 * @param overdue         巡检逾期注记（true=当日巡检 <2 次或最近一次距今 >6h——读时惰性判定，
 *                        delay 队列档位归 W-27 PR-4），非空
 * @param createdAt       建档时刻，非空
 */
public record ColdChainArchiveVO(
        Long id,
        String archiveNo,
        ColdChainPurpose purpose,
        String deviceId,
        TempRangeType tempRangeType,
        OffsetDateTime verifyDueAt,
        String inventoryDigest,
        boolean overdue,
        OffsetDateTime createdAt) {

    /**
     * 实体 → 出网视图（唯一转换出口；overdue 由服务层读时惰性判定传入）。
     *
     * @param entity  冷链档案实体，非空；来源：mapper 查询或落库组装
     * @param overdue 巡检逾期判定结果，非空
     * @return 冷链档案视图，非空
     */
    public static ColdChainArchiveVO from(ColdChainArchiveEntity entity, boolean overdue) {
        return new ColdChainArchiveVO(
                entity.getId(),
                entity.getArchiveNo(),
                entity.getPurpose(),
                entity.getDeviceId(),
                entity.getTempRangeType(),
                entity.getVerifyDueAt(),
                entity.getInventoryDigest(),
                overdue,
                entity.getCreatedAt());
    }
}
