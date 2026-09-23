package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 交接班完成入参（POST /api/v1/nursing/handovers/{handoverNo}/complete）：接班护士确认完成，
 * 双签同刻落定（交班签名=生成时刻、接班签名=完成时刻）。SBAR 四段可空——空/空串保留生成
 * 初稿文本不被覆盖（COALESCE(NULLIF) 语义），非空则覆盖为人工补充终稿。
 *
 * @param incomingNurseId     接班护士工号，必填；来源：操作者录入（接班签署方）
 * @param sbarSituation       S 现状补充文本，可空（空=保留汇总初稿）；来源：操作者补充
 * @param sbarBackground      B 背景补充文本，可空（空=保留汇总初稿）；来源：操作者补充
 * @param sbarAssessment      A 评估补充文本，可空（空=保留汇总初稿）；来源：操作者补充
 * @param sbarRecommendation  R 建议补充文本，可空（空=保留汇总初稿）；来源：操作者补充
 */
public record HandoverCompleteRequest(
        @NotBlank(message = "接班护士工号不能为空") String incomingNurseId,
        String sbarSituation,
        String sbarBackground,
        String sbarAssessment,
        String sbarRecommendation) {}
