package com.fuyun.patient.dto;

/**
 * 账户流水查询参数（GET /card-accounts/{id}/txns）：路径变量与分页参数的载体。
 *
 * @param accountId 账户 id，非空；来源：路径变量
 * @param page      页码（0 基）；来源：查询参数
 * @param size      单页条数（1-200，越界收敛）；来源：查询参数
 */
public record CardTxnQuery(long accountId, int page, int size) {}
