package com.fuyun.ward.vo;

import com.fuyun.ward.entity.ColdChainRecordEntity;
import com.fuyun.ward.enums.ColdChainRecordType;
import java.time.OffsetDateTime;

/**
 * 冷链记录视图对象（冷链记录出网载体）：记录全字段出网（双人核对与告警号回溯留痕）。
 *
 * @param id             记录行雪花 id，非空
 * @param recordNo       记录业务号，非空
 * @param archiveNo      所属档案号，非空
 * @param recordType     记录类型，非空
 * @param alarmRef       关联告警号（ALARM_HANDLE 非空），可空
 * @param secondOperator 双人核对第二人（ALARM_HANDLE 非空），可空
 * @param content        记录内容 JSON 文本，可空
 * @param recordedBy     登记人，非空
 * @param recordedAt     登记时刻，非空
 */
public record ColdChainRecordVO(
        Long id,
        String recordNo,
        String archiveNo,
        ColdChainRecordType recordType,
        String alarmRef,
        String secondOperator,
        String content,
        String recordedBy,
        OffsetDateTime recordedAt) {

    /**
     * 实体 → 出网视图（唯一转换出口，字段一一对应浅拷贝）。
     *
     * @param entity 冷链记录实体，非空；来源：mapper 查询或落库组装
     * @return 冷链记录视图，非空
     */
    public static ColdChainRecordVO from(ColdChainRecordEntity entity) {
        return new ColdChainRecordVO(
                entity.getId(),
                entity.getRecordNo(),
                entity.getArchiveNo(),
                entity.getRecordType(),
                entity.getAlarmRef(),
                entity.getSecondOperator(),
                entity.getContent(),
                entity.getRecordedBy(),
                entity.getRecordedAt());
    }
}
