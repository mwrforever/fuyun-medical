package com.fuyun.ward.dto;

import com.fuyun.ward.enums.ColdChainRecordType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 冷链记录登记请求（POST /api/v1/ward/cold-chain/archives/{archiveNo}/records 请求体）：
 * 巡检登记（INSPECTION）、告警处置（ALARM_HANDLE）、偏差登记（DEVIATION）三类型共用入口，
 * ALARM_HANDLE 必填 alarm_ref+second_operator（服务层校验，WD-1005）。
 *
 * @param recordType     记录类型，非空；来源：请求体
 * @param alarmRef       关联告警号（ALARM_HANDLE 必填；其余类型不校验不置空、按请求原样落库），可空；来源：请求体
 * @param secondOperator 双人核对第二人（ALARM_HANDLE 必填；其余类型不校验不置空、按请求原样落库），可空；来源：请求体
 * @param content        记录内容 JSON 文本（巡检读数/处置措施/偏差描述），可空；来源：请求体
 */
public record RegisterColdChainRecordRequest(
        @NotNull(message = "recordType 不能为空") ColdChainRecordType recordType,
        @Size(max = 32, message = "alarmRef 最长 32 字符") String alarmRef,
        @Size(max = 64, message = "secondOperator 最长 64 字符") String secondOperator,
        @Size(max = 4000, message = "content 最长 4000 字符") String content) {}
