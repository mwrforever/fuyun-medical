package com.fuyun.pharmacy.dto;

/**
 * 取药凭证核验入参（POST /dispenses/{no}/verify 可选 body，PR-5 Task 11 凭证载体交付——裁决 8）：
 * 凭证=结算单号（settlementNo），与处方归属的一致性由服务层经 billing SettlementQueryPort.settledUnder
 * 反查核验（该结算单下存在该处方 SETTLED 费用行即通过）；body 缺省或 credential 空均跳过核验
 * （追溯码逐码采集维持防回流主道——PR-4 注记⑧豁免面就此闭合）。
 *
 * @param credential 取药凭证（结算单号），可空
 */
public record VerifyCredentialRequest(String credential) {}
