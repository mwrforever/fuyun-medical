package com.fuyun.iot.dto;

import com.fuyun.iot.enums.ProductSyncStatus;

/**
 * 产品分页查询请求（GET /api/v1/iot/products 查询参数载体）：同步状态过滤可空，分页缺省
 * 0/20（0 基，与全项目分页口径一致）。
 *
 * @param page       页码（0 基），可空（缺省 0）；来源：管理台分页控件
 * @param size       单页条数，可空（缺省 20）
 * @param syncStatus 同步状态过滤，可空（缺省不过滤）；来源：失配巡检/状态筛选
 */
public record ProductQueryRequest(Integer page, Integer size, ProductSyncStatus syncStatus) {}
