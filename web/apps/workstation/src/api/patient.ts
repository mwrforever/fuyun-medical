/**
 * 患者域 API（web A.3-5 按业务域模块化）：建档/预检/检索/详情/冻结五操作。
 * 路径与后端契约对齐（baseURL 已含 /api）：/v1/patient/**；长整型 id 后端经 Jackson 以字符串输出
 * （backend A.3-8），前端类型一律 string 承载（web A.3-6 禁 number）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源；红路径降级时本文件仅此段替换为 types/patient 导入） */
export type PatientVO = components['schemas']['PatientVO'];
export type MatchCheckVO = components['schemas']['PatientMatchCheckVO'];
export type PatientCreateRequest = components['schemas']['PatientCreateRequest'];
export type MatchCheckRequest = components['schemas']['PatientMatchCheckRequest'];

/**
 * 建档前匹配预检（只读不落库）。
 *
 * @param payload 预检入参（姓名/性别必填）；来源：建档表单
 * @return 匹配结论（AUTO_MATCH/SUSPECT/NO_MATCH）；失败由响应拦截器统一弹错并上抛
 */
export async function matchCheck(payload: MatchCheckRequest): Promise<MatchCheckVO> {
  const resp = await http.post<MatchCheckVO>('/v1/patient/patients/match-check', payload);
  return resp.data;
}

/**
 * 患者建档。
 *
 * @param payload 建档请求（知情同意引用必填）；来源：建档表单
 * @return 建档结果（新建为 patientId=candidatePatientId）
 */
export async function createPatient(payload: PatientCreateRequest): Promise<MatchCheckVO> {
  const resp = await http.post<MatchCheckVO>('/v1/patient/patients', payload);
  return resp.data;
}

/** 患者检索查询参数对象（web A.7-1 形参对象化） */
export interface PatientSearchParams {
  /** 检索词（证件号/手机号/姓名），空串=空数据页 */
  keyword: string;
  /** 页码（0 基） */
  page: number;
  /** 单页条数 */
  size: number;
}

/**
 * 患者检索（脱敏分页）。
 *
 * @param params 查询参数；来源：检索页
 * @return 脱敏分页（content/page/size/total）
 */
export async function searchPatients(params: PatientSearchParams): Promise<{
  content: PatientVO[];
  page: number;
  size: number;
  total: number;
}> {
  const resp = await http.get<{
    content: PatientVO[];
    page: number;
    size: number;
    total: number;
  }>('/v1/patient/patients/search', { params });
  return resp.data;
}

/**
 * 患者详情（脱敏输出）。
 *
 * @param patientId 患者 id（string 承载雪花 ID）
 * @return 脱敏档案
 */
export async function getPatient(patientId: string): Promise<PatientVO> {
  const resp = await http.get<PatientVO>(`/v1/patient/patients/${patientId}`);
  return resp.data;
}

/**
 * 冻结/解冻患者（成对动作）。
 *
 * @param patientId 患者 id
 * @param freeze    true=冻结（携原因），false=解冻
 * @param reason    冻结原因（freeze=true 必填）
 */
export async function changeFreeze(
  patientId: string,
  freeze: boolean,
  reason?: string,
): Promise<void> {
  if (freeze) {
    await http.post(`/v1/patient/patients/${patientId}/freeze`, { reason: reason ?? '' });
    return;
  }
  await http.post(`/v1/patient/patients/${patientId}/unfreeze`);
}
