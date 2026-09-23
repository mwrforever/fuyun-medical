package com.fuyun.nursing.vo;

import java.util.List;
import java.util.Map;

/**
 * 病区护理配置出参（GET /api/v1/nursing/wards/{wardId}/config）：
 * JSONB 列服务端解析后的结构化面——体征测量频次（Task 5 消费）与班次定义（交接班 Task 9 /
 * 详情卡当班判定消费）。iotAutocastEnabled 为 P2 生效注记的原值透出。
 *
 * @param wardId             病区编码
 * @param vitalFreqMinutes   体征测量频次（分钟/次，键=NursingLevel code；如 SPECIAL=60/CRITICAL=240/NORMAL=480）
 * @param iotAutocastEnabled IoT 自动落卡开关（P2 生效；ICU 病区经此关闭防双写）
 * @param shifts             班次定义清单（V801 种子：DAY/EVENING/NIGHT 三班）
 */
public record WardConfigVO(
        String wardId,
        Map<String, Integer> vitalFreqMinutes,
        boolean iotAutocastEnabled,
        List<ShiftDefinition> shifts) {

    /**
     * 班次定义（shift_definitions JSONB 数组元素结构）。
     *
     * @param code  班次 code（如 DAY/EVENING/NIGHT）
     * @param name  班次展示名（如 白班）
     * @param start 起始时刻（HH:mm，含）
     * @param end   结束时刻（HH:mm，不含；跨零点班次 start &gt; end）
     */
    public record ShiftDefinition(String code, String name, String start, String end) {}
}
