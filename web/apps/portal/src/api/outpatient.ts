/**
 * portal 门诊预约 API（web A.3-5 模块化；裁决 13 免登录通道）：portal 号源查询 / portal 预约。
 * 路径前缀 /v1/outpatient/portal/**（baseURL 已含 /api）；雪花 id 一律 string 承载（web A.3-6）。
 * 后端 4xx 业务错误码 → 患者可读文案的映射常量集中本文件导出（§5.4，禁组件内散写）。
 */
import { http } from './http';
import type { PortalApiError } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type NumberPoolVO = components['schemas']['NumberPoolVO'];
export type PortalAppointmentRequest = components['schemas']['PortalAppointmentRequest'];
export type AppointmentVO = components['schemas']['AppointmentVO'];

/**
 * 后端业务错误码 → 患者可读文案映射（§5.4 定稿口径逐字采用）：
 * OP-1003 号源不足（并发约满）；OP-1006 爽约限约期内 / OP-1007 患者冻结拦截（两码同文案转译，
 * 患者侧统一以「先完成缴费」口径引导）。未映射错误码回退 ProblemDetail.detail 原文。
 */
export const OP_ERROR_COPY: Readonly<Record<string, string>> = {
  'OP-1003': '该号源刚被约满，请选择其他时段',
  'OP-1006': '该证件存在未完成缴费的挂号，请先完成缴费',
  'OP-1007': '该证件存在未完成缴费的挂号，请先完成缴费',
} as const;

/**
 * 按错误码解析患者可读文案：映射表命中取转译文案，否则回退后端 detail 原文。
 *
 * @param error 归一化业务错误（api 层 PortalApiError）；来源：预约提交失败
 * @return 患者可读文案（贴字段/错误条呈现）
 */
export function resolveErrorCopy(error: PortalApiError): string {
  if (error.errorCode !== null && OP_ERROR_COPY[error.errorCode] !== undefined) {
    return OP_ERROR_COPY[error.errorCode];
  }
  return error.detail;
}

/** 介质词表（与后端 portal 通道准入词表同源：身份证 / 就诊卡二选一） */
export const CREDENTIAL_TYPES = ['ID_CARD', 'VISIT_CARD'] as const;
export type CredentialType = (typeof CREDENTIAL_TYPES)[number];

/**
 * portal 可约号源查询（免登录只读面；余号 remaining 后端算好，前端零运算）。
 *
 * @param params deptCode 诊区编码 + date 就诊日（yyyy-MM-dd）；来源：第 2 步用户选择
 * @return 号源池列表；空列表=当日无可约号源
 */
export async function listPortalPools(params: {
  deptCode: string;
  date: string;
}): Promise<NumberPoolVO[]> {
  const resp = await http.get<NumberPoolVO[]>('/v1/outpatient/portal/schedules', { params });
  return resp.data;
}

/**
 * portal 预约提交（免登录；并发双道闸/限购/信用拦截由后端承载，失败经 PortalApiError 上抛）。
 *
 * @param payload credentialType 介质类型（ID_CARD/VISIT_CARD）+ credentialNo 证件号 + poolId 号源；
 *                来源：第 1/2 步表单
 * @return 预约单出参（apptNo 出票号 + payDeadline 支付时限，出票卡数据源）
 */
export async function bookPortalAppointment(
  payload: PortalAppointmentRequest,
): Promise<AppointmentVO> {
  const resp = await http.post<AppointmentVO>('/v1/outpatient/portal/appointments', payload);
  return resp.data;
}
