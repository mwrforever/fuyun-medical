package com.fuyun.ward.api;

import java.time.Instant;

/**
 * 冷链告警处置归档事件载荷（ward.cold-chain.alert-archived，V1102 id 82 冻结契约）：ALARM_HANDLE
 * 处置记录登记完成时发布，M05 据此驱动督办联动，冷链台账闭环消费。
 *
 * @param archiveNo 冷链档案业务号，非空；来源：处置记录归属档案
 * @param recordNo  处置记录业务号，非空；来源：ALARM_HANDLE 记录登记链签发
 * @param alarmRef  关联告警号，非空（ALARM_HANDLE 必填）；来源：iot.alarm.triggered 落行的告警号
 * @param purpose   冷链用途，非空；值域 VACCINE/BLOOD/REAGENT/PHARMA；来源：归属档案
 * @param handledBy 处置人（双人核对主操作者），非空；来源：登记操作者上下文
 * @param handledAt 处置时刻（UTC），非空；来源：登记完成时点
 */
public record ColdChainAlertArchivedPayload(
        String archiveNo, String recordNo, String alarmRef, String purpose, String handledBy, Instant handledAt) {}
