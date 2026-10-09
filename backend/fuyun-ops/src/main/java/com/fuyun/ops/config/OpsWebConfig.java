package com.fuyun.ops.config;

import com.fuyun.ops.controller.OpsWorkbenchController;
import com.fuyun.ops.service.impl.OpsWorkbenchServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M19 运营域 Web/服务装配集中点（OutpatientWebConfig 同款显式 @Import 形态——backend 宪法
 * B.1 装配归 app：本类由 fuyun-app OpsConfig @Import 生效，禁组件扫描放宽；本模块零 mapper
 * ——分析只读域零自身数据访问，取数全经四兄弟模块统计 Port api 面）。批次 2 册 2 首切片：
 * 工作台聚合服务与两读端点。
 */
@Configuration
@Import({OpsWorkbenchServiceImpl.class, OpsWorkbenchController.class})
public class OpsWebConfig {}
