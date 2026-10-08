/**
 * 运营工作台聚合域 API（M19 首切片前端面，一域一文件）：工作台总览快照（指标六格 +
 * 14 日接诊趋势 + 各诊区候诊表）与工作台事件流（轮询源事件 + STOMP 主题指引 + 危急值段）。
 * 路径前缀 /v1/ops/workbench/**（baseURL 已含 /api）；后端 Redis read-through TTL 5s
 * 承载总览快照，前端轮询节奏 ≥30s 不击穿缓存窗。
 * 雪花 id 与计数金额一律 string 承载（web A.3-6）：本域 amountFen 仅展示（fenToYuanDisplay
 * 集中换算），趋势/候诊计数字段透传零运算（周同比在视图层由两组真实和值推导）。
 * 契约类型取 openapi-typescript 生成物（web A.3-3），字段全可选——消费方逐字段判空，
 * 缺失渲染零值/占位不造数。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type WorkbenchOverviewVO = components['schemas']['WorkbenchOverviewVO'];
export type WorkbenchMetrics = components['schemas']['Metrics'];
export type TrendPoint = components['schemas']['TrendPoint'];
export type WaitingRow = components['schemas']['WaitingRow'];
export type WorkbenchEventsVO = components['schemas']['WorkbenchEventsVO'];
export type WorkEvent = components['schemas']['WorkEvent'];
export type WorkTopic = components['schemas']['Topic'];

/**
 * 事件类型词表（后端 WorkEvent.type 枚举展示映射，冻结 REST 面两值；扩充属契约变更）：
 * FEE_PENDING=待支付费用行（charged_at 降序）、DISPENSE_PENDING=在途待配药单。
 * 词表外的未知编码原样展示编码本身（不猜测语义，诚实呈现）。
 */
export const EVENT_TYPE_LABELS: Record<string, string> = {
  FEE_PENDING: '待支付费用',
  DISPENSE_PENDING: '待配药处方',
};

/**
 * 事件来源域词表（后端 WorkEvent.source 枚举展示映射）：billing=收费域、pharmacy=药房域。
 * 词表外的未知编码原样展示编码本身。
 */
export const EVENT_SOURCE_LABELS: Record<string, string> = {
  billing: '收费管理',
  pharmacy: '药房管理',
};

/** 运营工作台资源组：总览快照 / 事件流两聚合端点（只读分析域，零写面）。 */
export const dashboard = {
  /**
   * 工作台总览快照：指标六格（todayVisits/waitingCount/todayIncomeFen/inHospitalCount/
   * pendingDispenseCount/pendingSettleCount）+ 恒 14 点接诊趋势（含零填充日，stat_date 升序）
   * + 各诊区候诊表（候诊人数降序，仅观测科室入表）。后端聚合恒返回非空 VO（零值视图），
   * 空数据以零值/空清单表达，前端不做空对象兜底造假。
   *
   * @return 总览快照 VO；网络/鉴权失败由拦截器统一弹错并拒绝（调用方走错误态）
   */
  overview: async (): Promise<WorkbenchOverviewVO> => {
    const resp = await http.get<WorkbenchOverviewVO>('/v1/ops/workbench/overview');
    return resp.data;
  },
  /**
   * 工作台事件流：轮询源事件行（occurredAt 降序 ≤50 条）+ 三条 STOMP 主题指引（endpoint/topic
   * 模板/description，前端多端点订阅登记面）+ 危急值段（criticalValues 当前恒空 +
   * criticalValueDegraded=true 判别标志——M07 检验域未建，前端按标志渲染降级文案）。
   *
   * @return 事件流 VO；网络/鉴权失败由拦截器统一弹错并拒绝（调用方走错误态）
   */
  events: async (): Promise<WorkbenchEventsVO> => {
    const resp = await http.get<WorkbenchEventsVO>('/v1/ops/workbench/events');
    return resp.data;
  },
};
