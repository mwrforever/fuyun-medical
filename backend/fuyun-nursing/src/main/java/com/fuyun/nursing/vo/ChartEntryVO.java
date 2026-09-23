package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.TemperatureChartEntry;
import java.time.OffsetDateTime;

/**
 * 体温单条目出参（三段分组共用行载体：vitals/specialEvents/dailyValues）。entryType 为分组键，
 * typeKey 为唯一约束第四维回读（VITAL=体温部位 / SPECIAL_EVENT=事件类型 / DAILY_VALUE=日行值
 * 类型）；体征符号由前端按体温部位渲染，服务端仅承载类型权威。
 *
 * @param id              条目 id
 * @param entryTime       条目时点
 * @param entryType       条目类型（ChartEntryType code）
 * @param typeKey         类型键（唯一约束第四维回读）
 * @param vitalRef        体征记录引用（VITAL 条目）
 * @param specialEventType 特殊事件类型（SPECIAL_EVENT 条目）
 * @param dailyValueType  日行值类型（DAILY_VALUE 条目）
 * @param valueText       日行值文本
 * @param recorderId      记录人
 * @param recorderName    记录人姓名
 * @param remark          备注（物理降温前体温等）
 */
public record ChartEntryVO(
        Long id,
        OffsetDateTime entryTime,
        String entryType,
        String typeKey,
        Long vitalRef,
        String specialEventType,
        String dailyValueType,
        String valueText,
        String recorderId,
        String recorderName,
        String remark) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity 体温单条目行，非空
     * @return 条目出参，非空
     */
    public static ChartEntryVO from(TemperatureChartEntry entity) {
        return new ChartEntryVO(
                entity.getId(),
                entity.getEntryTime(),
                entity.getEntryType(),
                entity.getTypeKey(),
                entity.getVitalRef(),
                entity.getSpecialEventType(),
                entity.getDailyValueType(),
                entity.getValueText(),
                entity.getRecorderId(),
                entity.getRecorderName(),
                entity.getRemark());
    }
}
