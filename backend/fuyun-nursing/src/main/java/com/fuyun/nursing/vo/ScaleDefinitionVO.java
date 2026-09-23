package com.fuyun.nursing.vo;

import com.fuyun.nursing.constants.ScaleDefinition;
import java.util.List;
import java.util.Map;

/**
 * 评估量表定义出参（GET /api/v1/nursing/assessment-scales，前端渲染评估表单的唯一数据源）。
 * choices 为条目允许分值全量枚举（与创建侧范围校验同源，防两端漂移）；判级阈值判定函数为
 * 引擎内部逻辑不外发（前端按 riskLevel 结果渲染，不做本地判级）。
 *
 * @param scaleType  量表类型 code（ScaleType 词表：BRADEN/MORSE/NRS/BARTHEL/MEWS）
 * @param itemCodes  条目 code 清单（评估表单渲染序）
 * @param itemLabels 条目中文标签（与 itemCodes 按位对齐）
 * @param choices    条目取值域（itemCode → 允许分值清单）
 * @param totalRule  总分规则 code（SUM 条目求和）
 */
public record ScaleDefinitionVO(
        String scaleType,
        List<String> itemCodes,
        List<String> itemLabels,
        Map<String, List<Integer>> choices,
        String totalRule) {

    /**
     * 常量定义→出参静态工厂（code 逐字透传，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param definition 量表冻结定义，非空；来源：NursingScaleConstants
     * @return 量表定义出参，非空
     */
    public static ScaleDefinitionVO from(ScaleDefinition definition) {
        return new ScaleDefinitionVO(
                definition.scaleType().getCode(),
                definition.itemCodes(),
                definition.itemLabels(),
                definition.choices(),
                definition.totalRule());
    }
}
