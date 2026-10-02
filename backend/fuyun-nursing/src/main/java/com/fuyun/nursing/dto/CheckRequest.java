package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 执行单三向扫码核对入参（POST /api/v1/nursing/executions/{no}/check）：单次核对一个维度
 * （codeType 承载），PASS→CHECKED 迁移 + 流水落行；FAIL→NS-1022 + 流水落行（fail_type
 * 判定）不迁移状态。核对护士取操作者上下文（登录护士即核对主体）。
 *
 * @param code     扫码原文（腕带就诊编码/输液袋签码/执行单条码），非空（≤64）；来源：PDA 扫码
 * @param codeType 核对方式（WRISTBAND 腕带 / BAG_LABEL 瓶签 / DEVICE 执行单扫码），非空；来源：PDA
 *                 扫码场景选择，词表外值 NS-1019 拒绝
 */
public record CheckRequest(
        @NotBlank(message = "扫码原文必填（code）") @Size(max = 64, message = "扫码原文超长（≤64）")
        String code,

        @NotBlank(message = "核对方式必填（codeType）") String codeType) {}
