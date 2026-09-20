/**
 * 药事域 API（web A.3-5 模块化）：药品字典/开方作废/调剂三段/退药受理/占用查询。
 * 路径前缀 /v1/pharmacy/**（baseURL 已含 /api）；雪花 id 与数量一律 string 承载（web A.3-6），
 * 本域无金额运算面（计费权威在 M13）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';
// 分页壳（shared 手写声明）；import 恒置顶（lint import 序）
import type { PageResult } from '@fuyun/shared';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type DrugVO = components['schemas']['DrugVO'];
export type DrugSaveRequest = components['schemas']['DrugSaveRequest'];
export type InsuranceMappingRequest = components['schemas']['InsuranceMappingRequest'];
export type PrescriptionVO = components['schemas']['PrescriptionVO'];
export type PrescriptionCreateRequest = components['schemas']['PrescriptionCreateRequest'];
export type DispenseVO = components['schemas']['DispenseVO'];
export type PickRequest = components['schemas']['PickRequest'];
export type DispenseReturnRequest = components['schemas']['DispenseReturnRequest'];
export type OccupancyVO = components['schemas']['OccupancyVO'];

/** 选药检索（keyword/essential/antibioClass/insuranceMapped；默认启用面）。 */
export async function searchDrugs(params: {
  keyword?: string;
  essential?: boolean;
  antibioClass?: string;
  insuranceMapped?: boolean;
  page?: number;
  size?: number;
}): Promise<PageResult<DrugVO>> {
  const resp = await http.get<PageResult<DrugVO>>('/v1/pharmacy/drugs/search', { params });
  return resp.data;
}

/** 药品建档。 */
export async function createDrug(payload: DrugSaveRequest): Promise<DrugVO> {
  const resp = await http.post<DrugVO>('/v1/pharmacy/drugs', payload);
  return resp.data;
}

/** 药品档案变更。 */
export async function updateDrug(id: string, payload: DrugSaveRequest): Promise<DrugVO> {
  const resp = await http.put<DrugVO>(`/v1/pharmacy/drugs/${id}`, payload);
  return resp.data;
}

/** 医保编码对照（变更类型 MAPPING 广播；对照后 insuredSettleable 转 true）。 */
export async function mapInsurance(id: string, payload: InsuranceMappingRequest): Promise<void> {
  await http.post(`/v1/pharmacy/drugs/${id}/insurance-mapping`, payload);
}

/** 开方（同步返回处方号+预检分级；操作者由后端登录上下文注入）。 */
export async function createPrescription(
  payload: PrescriptionCreateRequest,
): Promise<PrescriptionVO> {
  const resp = await http.post<PrescriptionVO>('/v1/pharmacy/prescriptions', payload);
  return resp.data;
}

/** 处方分页查询（visitId/patientId/rxNo/status；status=PENDING_DISPENSE 即发药队列）。 */
export async function listPrescriptions(params: {
  visitId?: string;
  patientId?: string;
  rxNo?: string;
  status?: string;
  page?: number;
  size?: number;
}): Promise<PageResult<PrescriptionVO>> {
  const resp = await http.get<PageResult<PrescriptionVO>>('/v1/pharmacy/prescriptions', {
    params,
  });
  return resp.data;
}

/** 处方作废（未缴费联动费用作废；已缴费后端拒并弹错引导退药/退费）。 */
export async function cancelPrescription(rxNo: string, reason: string): Promise<void> {
  await http.post(`/v1/pharmacy/prescriptions/${rxNo}/cancel`, { reason });
}

/** 按处方号查发药单（工作台回显；定版出参 List<DispenseVO>，无单为空数组由调用方判空取首行）。 */
export async function listDispenses(params: { rxNo: string }): Promise<DispenseVO[]> {
  const resp = await http.get<DispenseVO[]>('/v1/pharmacy/dispenses', { params });
  return resp.data;
}

/** 配药（FEFO 选批+批次锁定；追溯码逐码录入，「无码不结」）。 */
export async function pickDispense(dispenseNo: string, payload: PickRequest): Promise<void> {
  await http.post(`/v1/pharmacy/dispenses/${dispenseNo}/pick`, payload);
}

/** 扫码核对（第二药师；同人由后端 PH-1011 拒）。 */
export async function verifyDispense(dispenseNo: string): Promise<void> {
  await http.post(`/v1/pharmacy/dispenses/${dispenseNo}/verify`);
}

/** 发药签名（终笔；发药即出库+计费占用）。 */
export async function issueDispense(dispenseNo: string): Promise<void> {
  await http.post(`/v1/pharmacy/dispenses/${dispenseNo}/issue`);
}

/** 退药受理（实物核验=追溯码逐码；mode=ISSUED_RETURN|DISPENSING_CANCEL）。 */
export async function createDispenseReturn(payload: DispenseReturnRequest): Promise<void> {
  await http.post('/v1/pharmacy/dispense-returns', payload);
}

/** 执行占用查询（M13 对接位）。 */
export async function listMedicationOccupancy(params: {
  patientId: string;
  visitId?: string;
  itemCode?: string;
}): Promise<OccupancyVO[]> {
  const resp = await http.get<OccupancyVO[]>('/v1/pharmacy/medication-occupancy', { params });
  return resp.data;
}
