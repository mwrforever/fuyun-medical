package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * PDA 巡视打卡入参（POST /api/v1/nursing/pda/patrol，Task 10）。标识三合一入口与
 * 就诊号双因子：服务面先经标识解析归一患者，再校验就诊号归属一致（防扫错腕带给错人
 * 打卡）；identifier 原样落 nursing_task.source_ref 留痕（扫码审计依据）。
 *
 * @param identifier 扫码标识（腕带就诊编码/就诊卡号/证件号三合一），必填；来源：PDA 扫码或手工录入
 * @param visitId    住院就诊号（归属校验键），必填；来源：PDA 当前患者上下文
 */
public record PdaPatrolRequest(
        @NotBlank(message = "扫码标识不能为空") String identifier,
        @NotBlank(message = "住院就诊号不能为空") String visitId) {}
