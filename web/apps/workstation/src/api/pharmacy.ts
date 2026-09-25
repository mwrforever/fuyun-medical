/**
 * 药事域 API（web A.3-5 模块化）：药品字典/开方作废/调剂三段/退药受理/占用查询/
 * 住院用药审方（M06 薄切片工作台：清单/通过/驳回）。
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
/** 审方任务行（医嘱号/患者号面/药品明细串/申请科室/状态/药师意见） */
export type ReviewTaskVO = components['schemas']['ReviewTaskVO'];
/** 审方任务分页出参（common PageResult 单泛型生成物：content/page/size/total） */
export type ReviewTaskPage = components['schemas']['PageResultReviewTaskVO'];
/** 审方决策入参（opinion 驳回必填由服务端守卫——缺/空白 PH-1020） */
export type ReviewDecisionRequest = components['schemas']['ReviewDecisionRequest'];

/** 审方任务状态选项（工作台状态过滤词表：待审/已通过/已驳回） */
export const REVIEW_TASK_STATUS_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'PENDING', label: '待审' },
  { code: 'APPROVED', label: '已通过' },
  { code: 'REJECTED', label: '已驳回' },
];

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

/** 住院用药审方资源组（M06 薄切片工作台）：清单（先到先审 FIFO）/通过/驳回。
 * 任务 id 为雪花 long 后端 string 化输出，前端 string 承载（web A.3-6）。 */
export const reviewTasks = {
  /** 审方工作台列表（状态过滤可空=全部；写操作后由调用方重拉刷新）。 */
  list: async (params: {
    status?: string;
    page: number;
    size: number;
  }): Promise<ReviewTaskPage> => {
    const resp = await http.get<ReviewTaskPage>('/v1/pharmacy/review-tasks', { params });
    return resp.data;
  },
  /** 审方通过（PENDING→APPROVED）：回执 pharmacy.medication-order.audit-completed 回流 M04
   * 置医嘱可执行；意见可选（body 可缺省）。 */
  approve: async (id: string, payload?: ReviewDecisionRequest): Promise<void> => {
    await http.post(`/v1/pharmacy/review-tasks/${id}/approve`, payload);
  },
  /** 审方驳回（PENDING→REJECTED）：意见必填由服务端守卫（缺/空白 PH-1020——400 先拦会破坏
   * 冻结语义）；回执 audit-rejected 驱动医生站修改重提。 */
  reject: async (id: string, payload: ReviewDecisionRequest): Promise<void> => {
    await http.post(`/v1/pharmacy/review-tasks/${id}/reject`, payload);
  },
};
