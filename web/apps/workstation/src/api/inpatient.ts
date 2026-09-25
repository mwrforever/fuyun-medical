/**
 * 住院域 API（M04 前端面，一域一文件）：入院登记（住院证建单/候床队列/预约/作废/登记确认/
 * 入科确认/欠费清单）+ 床位管理（床位图五态/预占/占床/释放/消毒完成/维修/恢复）+
 * 转科转床（四阶段编排/同病区轻量转床）。
 * 路径前缀 /v1/inpatient/**（baseURL 已含 /api）；雪花 id 与 long 后端经 Jackson 全局以
 * 字符串输出（backend A.3-8），前端类型一律 string 承载（web A.3-6）；金额字段零落地
 * （04 Spec 红线 3，住院计费权威在 M13，前端直调 billing 域）。
 * REST 面为后端 Task 3-13 冻结契约；函数按资源分组导出（spec mock 面）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type AdmissionVO = components['schemas']['AdmissionVO'];
export type InpatientVisitVO = components['schemas']['InpatientVisitVO'];
export type BedMapVO = components['schemas']['BedMapVO'];
export type ArrearsAlarmVO = components['schemas']['ArrearsAlarmVO'];
export type TransferResultVO = components['schemas']['TransferResultVO'];
export type AdmissionCreateRequest = components['schemas']['AdmissionCreateRequest'];
export type AdmissionScheduleRequest = components['schemas']['AdmissionScheduleRequest'];
export type VisitRegisterRequest = components['schemas']['VisitRegisterRequest'];
export type WardAdmitRequest = components['schemas']['WardAdmitRequest'];
export type BedAssignRequest = components['schemas']['BedAssignRequest'];
export type BedReserveRequest = components['schemas']['BedReserveRequest'];
export type TransferRequest = components['schemas']['TransferRequest'];
export type ChangeBedRequest = components['schemas']['ChangeBedRequest'];
/** 候床队列分页出参（common PageResult 单泛型生成物：content/page/size/total） */
export type AdmissionQueuePage = components['schemas']['PageResultAdmissionVO'];

/** 住院证状态选项（候床队列视图筛选词表：候床中/已预约；CANCELLED/COMPLETED 经「全部」透出） */
export const ADMISSION_STATUS_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'WAITING', label: '候床中' },
  { code: 'SCHEDULED', label: '已预约' },
];

/** 入院类型选项（后端 AdmissionCreateRequest 词表四值之业务三值：普通/急诊/预住院） */
export const ADMISSION_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'NORMAL', label: '普通入院' },
  { code: 'EMERGENCY', label: '急诊入院' },
  { code: 'PRE_HOSPITAL', label: '预住院' },
];

/** 住院证来源选项（后端 sourceType 词表四值：门诊转诊/急诊/体检/其他） */
export const SOURCE_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'OUTPATIENT', label: '门诊转诊' },
  { code: 'EMERGENCY', label: '急诊' },
  { code: 'PEIS', label: '体检' },
  { code: 'OTHER', label: '其他' },
];

/** 医保类型选项（insuranceType 为自由文本 ≤32，此处为登记台常用四值快捷项） */
export const INSURANCE_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: '职工医保', label: '职工医保' },
  { code: '居民医保', label: '居民医保' },
  { code: '自费', label: '自费' },
  { code: '商业保险', label: '商业保险' },
];

/** 床位五态中文词表（后端 BedStatus 五值；图例与卡片标签共用） */
export const BED_STATUS_LABELS: Record<string, string> = {
  FREE: '空床',
  RESERVED: '预占',
  OCCUPIED: '占床',
  DISINFECTING: '消毒中',
  MAINTENANCE: '维修中',
};

/** 病区选项（冻结 REST 面无病区清单端点，P1 以演示病区种子 W01 前端常量承载，
 * 与 nursing 域同源；P2 对齐 M01 组织机构病区后换接口源） */
export const WARD_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'W01', label: 'W01 演示病区' },
];

/** 入院登记资源组：住院证建单 / 候床队列分页 / 预约 / 作废 / 登记确认（红线方法）。 */
export const admissions = {
  /** 住院证登记（建单入 WAITING 候床队列；档案冻结 IP-1003 由后端 409 把守）。 */
  create: async (payload: AdmissionCreateRequest): Promise<AdmissionVO> => {
    const resp = await http.post<AdmissionVO>('/v1/inpatient/admissions', payload);
    return resp.data;
  },
  /** 候床队列分页（后端冻结排序=急诊优先＞预约时段＞候床时长；status 空=全部状态）。 */
  list: async (params: {
    status?: string;
    page: number;
    size: number;
  }): Promise<AdmissionQueuePage> => {
    const resp = await http.get<AdmissionQueuePage>('/v1/inpatient/admissions', { params });
    return resp.data;
  },
  /** 预约入院/预住院（WAITING→SCHEDULED；携目标床位时同事务联动床位预占）。 */
  schedule: async (no: string, payload: AdmissionScheduleRequest): Promise<AdmissionVO> => {
    const resp = await http.post<AdmissionVO>(`/v1/inpatient/admissions/${no}/schedule`, payload);
    return resp.data;
  },
  /** 住院证作废（候床/预约态→CANCELLED；宽容联动释放预占床位）。 */
  cancel: async (no: string): Promise<AdmissionVO> => {
    const resp = await http.post<AdmissionVO>(`/v1/inpatient/admissions/${no}/cancel`);
    return resp.data;
  },
  /** 入院登记确认（红线方法：同事务签发 I 型 visit_id 并落 REGISTERED）。 */
  register: async (no: string, payload: VisitRegisterRequest): Promise<InpatientVisitVO> => {
    const resp = await http.post<InpatientVisitVO>(
      `/v1/inpatient/admissions/${no}/register`,
      payload,
    );
    return resp.data;
  },
};

/** 在院就诊资源组：入科确认 / 病区欠费清单。 */
export const visits = {
  /** 入科确认（REGISTERED→ADMITTED；同事务联动床位 RESERVED→OCCUPIED 开账）。 */
  admitWard: async (visitId: string, payload: WardAdmitRequest): Promise<InpatientVisitVO> => {
    const resp = await http.post<InpatientVisitVO>(
      `/v1/inpatient/visits/${visitId}/admit-ward`,
      payload,
    );
    return resp.data;
  },
  /** 病区欠费清单（arrears_flag 在院聚合，患者姓名后端脱敏出网，工作站仅列表可见）。 */
  arrears: async (wardId: string): Promise<ArrearsAlarmVO[]> => {
    const resp = await http.get<ArrearsAlarmVO[]>('/v1/inpatient/visits/arrears', {
      params: { wardId },
    });
    return resp.data;
  },
};

/** 床位资源组：床位图 / 预占 / 占床 / 释放 / 消毒完成 / 转维修 / 维修恢复。 */
export const beds = {
  /** 病区床位图聚合（五态+包床+性别限制+占用摘要；床号升序）。 */
  map: async (wardId: string): Promise<BedMapVO[]> => {
    const resp = await http.get<BedMapVO[]>('/v1/inpatient/beds/map', { params: { wardId } });
    return resp.data;
  },
  /** 床位预占（FREE→RESERVED；预占不绑定就诊主体，契约形状固化空对象）。 */
  reserve: async (id: string, payload: BedReserveRequest): Promise<void> => {
    await http.post(`/v1/inpatient/beds/${id}/reserve`, payload);
  },
  /** 床位占床（FREE/RESERVED→OCCUPIED 直接分配快速通道，开占用流水）。 */
  assign: async (id: string, payload: BedAssignRequest): Promise<void> => {
    await http.post(`/v1/inpatient/beds/${id}/assign`, payload);
  },
  /** 释放床位预占（RESERVED→FREE；占用态须走转床/转科/出院编排）。 */
  release: async (id: string): Promise<void> => {
    await http.post(`/v1/inpatient/beds/${id}/release`);
  },
  /** 消毒完成确认（DISINFECTING→FREE，终末消毒完成回可分配池）。 */
  disinfectDone: async (id: string): Promise<void> => {
    await http.post(`/v1/inpatient/beds/${id}/disinfect-done`);
  },
  /** 床位转维修（FREE→MAINTENANCE）。 */
  maintain: async (id: string): Promise<void> => {
    await http.post(`/v1/inpatient/beds/${id}/maintain`);
  },
  /** 维修恢复（MAINTENANCE→FREE）。 */
  maintainDone: async (id: string): Promise<void> => {
    await http.post(`/v1/inpatient/beds/${id}/maintain-done`);
  },
};

/** 转科转床资源组：四阶段编排 / 同病区轻量转床。 */
export const transfer = {
  /** 转科四阶段编排（停嘱截断→在途三分→床位流转→事件链，单事务）。 */
  execute: async (visitId: string, payload: TransferRequest): Promise<TransferResultVO> => {
    const resp = await http.post<TransferResultVO>(
      `/v1/inpatient/visits/${visitId}/transfer`,
      payload,
    );
    return resp.data;
  },
  /** 同病区转床轻量路径（无停嘱步骤，床位流转+事件）。 */
  changeBed: async (visitId: string, payload: ChangeBedRequest): Promise<TransferResultVO> => {
    const resp = await http.post<TransferResultVO>(
      `/v1/inpatient/visits/${visitId}/change-bed`,
      payload,
    );
    return resp.data;
  },
};
