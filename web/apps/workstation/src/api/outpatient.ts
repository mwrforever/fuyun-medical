/**
 * 门诊域 API（web A.3-5 模块化）：排班模板/排班与放号/号源/预约/取号退号改期/分诊报到调级/
 * 队列叫号过号重呼快照/医生站接诊诊毕开单开方衔接/申请单查询。
 * 路径前缀 /v1/outpatient/**（baseURL 已含 /api）；雪花 id 与金额一律 string 承载（web A.3-6），
 * 本域无金额运算面（资金权威在 M13，挂号收费联动经 billing api 完成）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';
// 分页壳（shared 手写声明）；import 恒置顶（lint import 序）
import type { PageResult } from '@fuyun/shared';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type ScheduleTemplateVO = components['schemas']['ScheduleTemplateVO'];
export type ScheduleTemplateSaveRequest = components['schemas']['ScheduleTemplateSaveRequest'];
export type ScheduleVO = components['schemas']['ScheduleVO'];
export type ScheduleGenerateRequest = components['schemas']['ScheduleGenerateRequest'];
export type NumberPoolVO = components['schemas']['NumberPoolVO'];
export type StopScheduleRequest = components['schemas']['StopScheduleRequest'];
export type ExtraQuotaRequest = components['schemas']['ExtraQuotaRequest'];
export type AppointmentVO = components['schemas']['AppointmentVO'];
export type AppointmentCreateRequest = components['schemas']['AppointmentCreateRequest'];
export type CancelAppointmentRequest = components['schemas']['CancelAppointmentRequest'];
export type RescheduleRequest = components['schemas']['RescheduleRequest'];
export type VisitVO = components['schemas']['VisitVO'];
export type QueueTicketVO = components['schemas']['QueueTicketVO'];
export type CheckInRequest = components['schemas']['CheckInRequest'];
export type TriageAdjustRequest = components['schemas']['TriageAdjustRequest'];
export type QueueCallRequest = components['schemas']['QueueCallRequest'];
export type DoctorQueueItemVO = components['schemas']['DoctorQueueItemVO'];
export type ClinicOrderVO = components['schemas']['ClinicOrderVO'];
export type OrderCreateRequest = components['schemas']['OrderCreateRequest'];
export type PrescriptionOpenRequest = components['schemas']['PrescriptionOpenRequest'];
export type FinishVisitRequest = components['schemas']['FinishVisitRequest'];

/** 诊毕离院去向八项（V705 门诊字典种子 disposition 词表的前端常量清单，页面下拉唯一来源） */
export const DISPOSITION_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'DISCHARGE_HOME', label: '医嘱离院' },
  { code: 'TRANSFER_HOSPITAL', label: '医嘱转院' },
  { code: 'TRANSFER_COMMUNITY', label: '医嘱转社区' },
  { code: 'NON_MEDICAL_LEAVE', label: '非医嘱离院' },
  { code: 'DEATH', label: '死亡' },
  { code: 'OBSERVATION', label: '急诊留观' },
  { code: 'TRANSFER_INPATIENT', label: '急诊转住院' },
  { code: 'OTHER', label: '其他' },
] as const;

/** 排班模板分页清单。 */
export async function listTemplates(params: {
  page?: number;
  size?: number;
}): Promise<PageResult<ScheduleTemplateVO>> {
  const resp = await http.get<PageResult<ScheduleTemplateVO>>('/v1/outpatient/schedule-templates', {
    params,
  });
  return resp.data;
}

/** 登记排班模板。 */
export async function createTemplate(
  payload: ScheduleTemplateSaveRequest,
): Promise<ScheduleTemplateVO> {
  const resp = await http.post<ScheduleTemplateVO>('/v1/outpatient/schedule-templates', payload);
  return resp.data;
}

/** 更新排班模板。 */
export async function updateTemplate(
  payload: ScheduleTemplateSaveRequest,
): Promise<ScheduleTemplateVO> {
  const resp = await http.put<ScheduleTemplateVO>('/v1/outpatient/schedule-templates', payload);
  return resp.data;
}

/** T+N 放号生成（返回生成排班数；重试幂等由后端预过滤+唯一键承载）。 */
export async function generateSchedules(payload: ScheduleGenerateRequest): Promise<number> {
  const resp = await http.post<number>('/v1/outpatient/schedules/generate', payload);
  return resp.data;
}

/** 排班日历分页清单（deptCode/dateFrom/dateTo 过滤）。 */
export async function listSchedules(params: {
  deptCode?: string;
  dateFrom?: string;
  dateTo?: string;
  page?: number;
  size?: number;
}): Promise<PageResult<ScheduleVO>> {
  const resp = await http.get<PageResult<ScheduleVO>>('/v1/outpatient/schedules', { params });
  return resp.data;
}

/** 停诊（整池作废+已约患者联动，理由必填留痕）。 */
export async function stopSchedule(id: string, payload: StopScheduleRequest): Promise<void> {
  await http.post(`/v1/outpatient/schedules/${id}/stop`, payload);
}

/** 恢复停诊。 */
export async function resumeSchedule(id: string): Promise<void> {
  await http.post(`/v1/outpatient/schedules/${id}/resume`);
}

/** 可约号源查询（余量对外查询口径，remaining 后端算好，前端零运算）。 */
export async function listAvailablePools(params: {
  deptCode: string;
  date: string;
  apptType?: string;
}): Promise<NumberPoolVO[]> {
  const resp = await http.get<NumberPoolVO[]>('/v1/outpatient/number-pools/available', { params });
  return resp.data;
}

/** 加号授权（增量 total_quota 由后端落库并同步 Redis 池，前端传加号个数）。 */
export async function grantExtraQuota(id: string, payload: ExtraQuotaRequest): Promise<void> {
  await http.post(`/v1/outpatient/number-pools/${id}/extra-quota`, payload);
}

/** 挂号（窗口渠道；当日号 TAKEN 直出 visitId，预约号 RESERVED 等支付时限）。 */
export async function createAppointment(payload: AppointmentCreateRequest): Promise<AppointmentVO> {
  const resp = await http.post<AppointmentVO>('/v1/outpatient/appointments', payload);
  return resp.data;
}

/** 取号（预约单 → 就诊；超支付时限 OP-1008 拒绝）。 */
export async function takeAppointment(no: string): Promise<VisitVO> {
  const resp = await http.post<VisitVO>(`/v1/outpatient/appointments/${no}/take`);
  return resp.data;
}

/** 退号（线上退号时限/已报到状态由后端 OP-1009/OP-1010 把守）。 */
export async function cancelAppointment(
  no: string,
  payload: CancelAppointmentRequest,
): Promise<AppointmentVO> {
  const resp = await http.post<AppointmentVO>(`/v1/outpatient/appointments/${no}/cancel`, payload);
  return resp.data;
}

/** 改期（先占新号源后退旧单，reschedule_of 链由后端承载）。 */
export async function rescheduleAppointment(
  no: string,
  payload: RescheduleRequest,
): Promise<AppointmentVO> {
  const resp = await http.post<AppointmentVO>(
    `/v1/outpatient/appointments/${no}/reschedule`,
    payload,
  );
  return resp.data;
}

/** 分诊报到（visitId 换票入队；stationId 承载自助终端位，priorityFactors 优先级因子可选）。 */
export async function checkIn(payload: CheckInRequest): Promise<QueueTicketVO> {
  const resp = await http.post<QueueTicketVO>('/v1/outpatient/triage/check-in', payload);
  return resp.data;
}

/** 二次分诊/调级/转队列（action 词表由后端校验，前端透传）。 */
export async function adjustTriage(payload: TriageAdjustRequest): Promise<QueueTicketVO> {
  const resp = await http.post<QueueTicketVO>('/v1/outpatient/triage/adjust', payload);
  return resp.data;
}

/** 候诊叫号（队首 CAS 取票；无候诊由后端拒）。 */
export async function callNext(payload: QueueCallRequest): Promise<QueueTicketVO> {
  const resp = await http.post<QueueTicketVO>('/v1/outpatient/queue/call', payload);
  return resp.data;
}

/** 过号（降级分重排不改号）。 */
export async function passTicket(id: string): Promise<QueueTicketVO> {
  const resp = await http.post<QueueTicketVO>(`/v1/outpatient/queue/tickets/${id}/pass`);
  return resp.data;
}

/** 重呼（同号再叫一次）。 */
export async function recallTicket(id: string): Promise<QueueTicketVO> {
  const resp = await http.post<QueueTicketVO>(`/v1/outpatient/queue/tickets/${id}/recall`);
  return resp.data;
}

/** 队列快照（分诊台轮询数据源；status 过滤可选）。 */
export async function getQueueSnapshot(params: {
  queueId: string;
  status?: string;
}): Promise<QueueTicketVO[]> {
  const resp = await http.get<QueueTicketVO[]>(`/v1/outpatient/queues/${params.queueId}/tickets`, {
    params: { status: params.status },
  });
  return resp.data;
}

/** 医生站候诊列表（我的队列；deptCode+doctorId 双参定位）。 */
export async function listPatientQueue(params: {
  deptCode: string;
  doctorId: string;
}): Promise<DoctorQueueItemVO[]> {
  const resp = await http.get<DoctorQueueItemVO[]>('/v1/outpatient/doctor/patient-queue', {
    params,
  });
  return resp.data;
}

/** 接诊（WAITING → IN_CONSULT；票面 doctor 过滤由后端承载）。 */
export async function admitVisit(visitId: string): Promise<VisitVO> {
  const resp = await http.post<VisitVO>(`/v1/outpatient/visits/${visitId}/admit`);
  return resp.data;
}

/** 诊毕（在途单据显式确认 explicitConfirm 未勾时后端拒；去向 disposition 八项词表）。 */
export async function finishVisit(visitId: string, payload: FinishVisitRequest): Promise<VisitVO> {
  const resp = await http.post<VisitVO>(`/v1/outpatient/visits/${visitId}/finish`, payload);
  return resp.data;
}

/** 医生站开单（检查检验/处置；quantity string 透传零运算）。 */
export async function createOrder(
  visitId: string,
  payload: OrderCreateRequest,
): Promise<ClinicOrderVO> {
  const resp = await http.post<ClinicOrderVO>(`/v1/outpatient/visits/${visitId}/orders`, payload);
  return resp.data;
}

/** 开立处方（M06 衔接；执业授权强校验由后端 PH-1018 承载，返回 RX_REF 引用单）。 */
export async function openPrescription(
  visitId: string,
  payload: PrescriptionOpenRequest,
): Promise<ClinicOrderVO> {
  const resp = await http.post<ClinicOrderVO>(
    `/v1/outpatient/visits/${visitId}/prescriptions`,
    payload,
  );
  return resp.data;
}

/** 按就诊号查询申请单（含 RX_REF 处方引用行，「已发药」镜像列数据源）。 */
export async function listOrdersByVisit(params: { visitId: string }): Promise<ClinicOrderVO[]> {
  const resp = await http.get<ClinicOrderVO[]>('/v1/outpatient/orders', { params });
  return resp.data;
}
