/**
 * M19 运营与决策支持域对外契约唯一出口（backend 宪法 B.1）：批次 2 册 2 首切片暂无跨模块
 * 对外接口与事件发布（工作台两聚合端点为纯消费面——读四业务模块统计 Port），预留
 * {@code @NamedInterface} 声明模块 api 面（后续批次指标中台/上报/绩效契约落此包）。
 */
@NamedInterface("api")
package com.fuyun.ops.api;

import org.springframework.modulith.NamedInterface;
