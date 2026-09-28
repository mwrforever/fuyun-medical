package com.fuyun.ward.vo;

import java.math.BigDecimal;

/**
 * 输液看板设备行视图（GET /api/v1/ward/infusion-board/{wardId} 出参 devices 内嵌载体）：
 * 单设备余量/滴速最新值与告警档位映射结果。
 *
 * @param deviceId       IoTDA 设备标识，非空
 * @param remainLatest   输液余量最新值（ml，遥测 series 末点 last 聚合值；无数据为 null），可空
 * @param dropRateLatest 滴速最新值（滴/分，遥测 series 末点 last 聚合值；无数据为 null），可空
 * @param alertLevel     告警档位（NONE 无/YELLOW 黄 ≤15ml/ORANGE 橙 ≤10ml/RED 红 ≤5ml；余量
 *                       无数据为 NONE——阈值 brief 冻结三档），非空
 */
public record InfusionBoardDeviceVO(
        String deviceId, BigDecimal remainLatest, BigDecimal dropRateLatest, String alertLevel) {

    /** 告警档位词表：无告警 */
    public static final String LEVEL_NONE = "NONE";

    /** 告警档位词表：黄档（≤15ml 提示档） */
    public static final String LEVEL_YELLOW = "YELLOW";

    /** 告警档位词表：橙档（≤10ml 警告档） */
    public static final String LEVEL_ORANGE = "ORANGE";

    /** 告警档位词表：红档（≤5ml 危急档，同时触发系统级呼叫落行） */
    public static final String LEVEL_RED = "RED";

    /**
     * 按余量值映射告警档位（RED 优先判定，brief 冻结 15/10/5 三档——阈值参照 iot alarm_rule
     * 配置面快照，本映射为展示口径）。
     *
     * @param remainLatest 余量最新值（ml），可空（无遥测数据）
     * @return 档位值 NONE/YELLOW/ORANGE/RED，非空
     */
    public static String mapAlertLevel(BigDecimal remainLatest) {
        if (remainLatest == null) {
            return LEVEL_NONE;
        }
        if (remainLatest.compareTo(BigDecimal.valueOf(5)) <= 0) {
            return LEVEL_RED;
        }
        if (remainLatest.compareTo(BigDecimal.valueOf(10)) <= 0) {
            return LEVEL_ORANGE;
        }
        if (remainLatest.compareTo(BigDecimal.valueOf(15)) <= 0) {
            return LEVEL_YELLOW;
        }
        return LEVEL_NONE;
    }
}
