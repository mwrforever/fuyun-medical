package com.fuyun.patient.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 匹配预检结果出参（建档表单交互与建档响应共用载体；outcome 词表 = MatchOutcome 枚举名）。
 *
 * @param outcome             结论：AUTO_MATCH 自动归一 / SUSPECT 疑似重复待审 / NO_MATCH 新建
 * @param candidatePatientId  归一候选既有档案 id（AUTO_MATCH 必填；SUSPECT 为矛盾命中方，可空）；
 *                            来源：EMPI 引擎；前端以 string 承载（全局 Long→String）
 * @param score               最高命中评分（0-100，一位小数；无候选为 null）；来源：评分规则
 * @param matchedRules        命中规则名清单（如 ["ID_CARD_EXACT"] / ["NAME_SEX_BIRTH"]）；来源：评分规则
 */
public record PatientMatchCheckVO(
        String outcome, Long candidatePatientId, BigDecimal score, List<String> matchedRules) {}
