/**
 * 收费域 API（web A.3-5 模块化）：划价/手工计费/费用查询/预结算/结算/退费审批/一日清单。
 * 路径前缀 /v1/billing/**（baseURL 已含 /api）；金额与雪花 id 一律 string 承载（web A.3-6，
 * D-18 根治后生成契约同源），前端零金额运算（总 Spec D5）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';
// 分页壳（shared 手写声明，total string——D-18 根治后与生成物同源）；import 恒置顶（lint import 序）
import type { PageResult } from '@fuyun/shared';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type QuoteVO = components['schemas']['QuoteVO'];
export type QuoteRequest = components['schemas']['QuoteRequest'];
export type FeeRecordVO = components['schemas']['FeeRecordVO'];
export type ManualChargeRequest = components['schemas']['ManualChargeRequest'];
export type SettlementPreviewVO = components['schemas']['SettlementPreviewVO'];
export type SettlementPreviewRequest = components['schemas']['SettlementPreviewRequest'];
export type SettleRequest = components['schemas']['SettleRequest'];
export type SettlementVO = components['schemas']['SettlementVO'];
export type RefundVO = components['schemas']['RefundVO'];
export type RefundApplyRequest = components['schemas']['RefundApplyRequest'];
export type DailyListVO = components['schemas']['DailyListVO'];

/**
 * 预计价（划价展示，不落库；金额后端按快照算，前端仅渲染）。
 * @param payload 计价请求（patientId/visitId/lines）；来源：划价表单
 * @return 逐行价与合计（分，string 渲染层换算）
 */
export async function quote(payload: QuoteRequest): Promise<QuoteVO> {
  const resp = await http.post<QuoteVO>('/v1/billing/pricing/quote', payload);
  return resp.data;
}

/**
 * 手工计费（红线 3：操作者由后端登录上下文注入，前端不传）。
 * @param payload 项目/数量/理由；来源：收费员补录
 * @return 新费用行 id（string）
 */
export async function manualCharge(payload: ManualChargeRequest): Promise<string> {
  const resp = await http.post<string>('/v1/billing/fees/manual', payload);
  return resp.data;
}

/**
 * 就诊费用分页查询（划价页回显 + 退费选行）。
 * @param params visitId + 0 基分页；来源：页面输入
 */
export async function listFees(params: {
  visitId: string;
  page?: number;
  size?: number;
}): Promise<PageResult<FeeRecordVO>> {
  const resp = await http.get<PageResult<FeeRecordVO>>('/v1/billing/fees', { params });
  return resp.data;
}

/** 预结算（自费=本地聚合 / 医保=模拟回执锁价），返回可结算草稿单。 */
export async function previewSettlement(
  payload: SettlementPreviewRequest,
): Promise<SettlementPreviewVO> {
  const resp = await http.post<SettlementPreviewVO>('/v1/billing/settlements/preview', payload);
  return resp.data;
}

/**
 * 正式结算（以预结算回传 settleNo + 支付明细 payments 出网；幂等由后端 settleNo 终态承载，
 * 前端不生成任何流水键；返回结算单终态）。payments 组装红线（2026-09-17 审查裁决①）：全现金
 * 场景单行 `{ method: 'CASH', amount: preview 回传 totalAmount }`（后端 string 分值直接透传，
 * 页面零运算，Σamount==totalAmount 勾稽由后端第二层校验兜底）；就诊卡支付加
 * `{ method: 'CARD_BALANCE', amount, channelRef: 选卡页回传卡账户 id string }` 行（可与现金混行）。
 */
export async function settle(payload: SettleRequest): Promise<SettlementVO> {
  const resp = await http.post<SettlementVO>('/v1/billing/settlements', payload);
  return resp.data;
}

/** 结算单查询（按结算编号）。 */
export async function getSettlement(settleNo: string): Promise<SettlementVO> {
  const resp = await http.get<SettlementVO>(`/v1/billing/settlements/${settleNo}`);
  return resp.data;
}

/** 退费申请（金额后端按明细聚合，前端只传费用行与数量；返回退费单 id）。 */
export async function applyRefund(payload: RefundApplyRequest): Promise<string> {
  const resp = await http.post<string>('/v1/billing/refunds', payload);
  return resp.data;
}

/** 退费单分页（审批工作台队列；status 可空=全部）。 */
export async function listRefunds(params: {
  status?: string;
  page?: number;
  size?: number;
}): Promise<PageResult<RefundVO>> {
  const resp = await http.get<PageResult<RefundVO>>('/v1/billing/refunds', { params });
  return resp.data;
}

/** 退费批准（双人守卫后端拒绝自审，403 经拦截器统一弹错）。 */
export async function approveRefund(refundId: string): Promise<void> {
  await http.post(`/v1/billing/refunds/${refundId}/approve`);
}

/** 退费驳回（理由必填）。 */
export async function rejectRefund(refundId: string, reason: string): Promise<void> {
  await http.post(`/v1/billing/refunds/${refundId}/reject`, { reason });
}

/** 退费执行（审批通过后原路退回）。 */
export async function executeRefund(refundId: string): Promise<void> {
  await http.post(`/v1/billing/refunds/${refundId}/execute`);
}

/** 一日清单（按日明细+大类汇总+合计，后端三层勾稽已保）。 */
export async function dailyList(visitId: string, date: string): Promise<DailyListVO> {
  const resp = await http.get<DailyListVO>('/v1/billing/daily-lists', {
    params: { visitId, date },
  });
  return resp.data;
}
