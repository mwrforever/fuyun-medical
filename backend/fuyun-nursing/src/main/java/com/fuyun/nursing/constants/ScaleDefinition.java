package com.fuyun.nursing.constants;

import com.fuyun.nursing.enums.RiskLevel;
import com.fuyun.nursing.enums.ScaleType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 评估量表定义载体（NursingScaleConstants 五量表冻结清单的行形态）：条目词表、取值域与
 * 判级阈值均为模块专业配置（Spec :249「量表为模块专业配置非国标字典」），随常量类冻结，
 * 不落字典表；出参暴露经 ScaleDefinitionVO（riskThresholds 判定函数为引擎内部逻辑，不外发）。
 *
 * @param scaleType      量表类型（ScaleType 五值词表），非空
 * @param itemCodes      条目 code 清单（评估表单渲染序），非空
 * @param itemLabels     条目中文标签（与 itemCodes 按位对齐），非空
 * @param choices        条目取值域（itemCode → 允许分值全量枚举；范围校验与前端选项同源），非空
 * @param totalRule      总分规则 code（SUM 条目求和，五量表统一），非空
 * @param riskThresholds 判级函数（总分 → 风险等级；阈值逐值冻结于 NursingScaleConstants，
 *                       各量表函数对可达总分域穷尽，无不可判级分支），非空
 */
public record ScaleDefinition(
        ScaleType scaleType,
        List<String> itemCodes,
        List<String> itemLabels,
        Map<String, List<Integer>> choices,
        String totalRule,
        Function<Integer, RiskLevel> riskThresholds) {}
