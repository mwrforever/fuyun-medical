/**
 * 护理域 API（M05 前端面，一域一文件）：病区患者（入区/出区/一览/详情）、责任护士分配、
 * 体征（录入/查询/待复核 confirm/reject）、体温单（月页查询/特殊事件）、出入量明细、
 * 护理记录单（创建/提交/修订）、护理评估（量表定义/提交/历史）、护理任务（列表/完成/取消）、
 * 交接班（生成/完成/清单）、PDA（患者摘要/巡视打卡）。
 * 路径前缀 /v1/nursing/**（baseURL 已含 /api）；雪花 id 与数量金额一律 string 承载
 * （web A.3-6），本域无金额运算面（quantity 透传零运算）。
 * REST 面为后端 Task 1-11 冻结契约；函数按资源分组导出（spec mock 面）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type WardPatientVO = components['schemas']['WardPatientVO'];
export type WardPatientDetailVO = components['schemas']['WardPatientDetailVO'];
export type WardPatientRegisterRequest = components['schemas']['WardPatientRegisterRequest'];
export type WardPatientRemoveRequest = components['schemas']['WardPatientRemoveRequest'];
export type NurseAssignmentVO = components['schemas']['NurseAssignmentVO'];
export type NurseAssignmentRequest = components['schemas']['NurseAssignmentRequest'];
export type VitalSignVO = components['schemas']['VitalSignVO'];
export type VitalSignRecordRequest = components['schemas']['VitalSignRecordRequest'];
export type VitalSignRejectRequest = components['schemas']['VitalSignRejectRequest'];
export type TemperatureChartVO = components['schemas']['TemperatureChartVO'];
export type ChartEntryVO = components['schemas']['ChartEntryVO'];
export type SpecialEventRequest = components['schemas']['SpecialEventRequest'];
export type IoRecordVO = components['schemas']['IoRecordVO'];
export type IoRecordCreateRequest = components['schemas']['IoRecordCreateRequest'];
export type NursingRecordVO = components['schemas']['NursingRecordVO'];
export type NursingRecordCreateRequest = components['schemas']['NursingRecordCreateRequest'];
export type NursingRecordReviseRequest = components['schemas']['NursingRecordReviseRequest'];
export type ScaleDefinitionVO = components['schemas']['ScaleDefinitionVO'];
export type NursingAssessmentVO = components['schemas']['NursingAssessmentVO'];
export type NursingAssessmentCreateRequest =
  components['schemas']['NursingAssessmentCreateRequest'];
export type NursingTaskVO = components['schemas']['NursingTaskVO'];
export type NursingTaskCancelRequest = components['schemas']['NursingTaskCancelRequest'];
export type ShiftHandoverVO = components['schemas']['ShiftHandoverVO'];
export type HandoverGenerateRequest = components['schemas']['HandoverGenerateRequest'];
export type HandoverCompleteRequest = components['schemas']['HandoverCompleteRequest'];
export type PdaPatientSummaryVO = components['schemas']['PdaPatientSummaryVO'];
export type PdaPatrolRequest = components['schemas']['PdaPatrolRequest'];

/**
 * 体温单部位 → 符号类名唯一映射（设计文档 §5.3 契约：AXILLARY 腋温×/ORAL 口温●/RECTAL 肛温〇，
 * spec 机器判据来源，导出供断言；实现零改名零增删）。
 */
export const TEMP_SITE_SYMBOL: Readonly<Record<string, string>> = {
  AXILLARY: 'fuy-temp-x',
  ORAL: 'fuy-temp-dot',
  RECTAL: 'fuy-temp-circle',
};

/** 体温测量部位选项（后端 TempSite 枚举三值展示映射） */
export const TEMP_SITE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'AXILLARY', label: '腋下' },
  { code: 'ORAL', label: '口腔' },
  { code: 'RECTAL', label: '直肠' },
];

/** 护理级别选项（后端 NursingLevel 枚举三值：特级/病重/普通——冻结 REST 面词表） */
export const NURSING_LEVEL_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'SPECIAL', label: '特级护理' },
  { code: 'CRITICAL', label: '病重护理' },
  { code: 'NORMAL', label: '普通护理' },
];

/** 病情标记选项（V801 condition_tags 词表五值，逗号分隔存储） */
export const CONDITION_TAG_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'CRITICAL', label: '病危' },
  { code: 'SEVERE', label: '病重' },
  { code: 'NEW', label: '新入' },
  { code: 'SURGERY', label: '手术' },
  { code: 'DELIVERY', label: '分娩' },
];

/** 特殊事件类型选项（后端 SpecialEventType 枚举十值展示映射） */
export const SPECIAL_EVENT_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'ADMISSION', label: '入院' },
  { code: 'SURGERY', label: '手术' },
  { code: 'DELIVERY', label: '分娩' },
  { code: 'TRANSFER_OUT', label: '转科' },
  { code: 'DISCHARGE', label: '出院' },
  { code: 'DEATH', label: '死亡' },
  { code: 'PHYSICAL_COOLING', label: '物理降温' },
  { code: 'PULSE_DEFICIT_START', label: '脉搏短绌起' },
  { code: 'PULSE_DEFICIT_END', label: '脉搏短绌止' },
  { code: 'CARDIAC_ARREST', label: '呼吸心跳停止' },
];

/** 病区选项（冻结 REST 面无病区清单端点，P1 以演示病区种子 W01 前端常量承载；
 * P2 对齐 M01 组织机构病区后换接口源） */
export const WARD_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'W01', label: 'W01 演示病区' },
];

/** 班次选项（V801 演示病区 shift_definitions 种子词表：白班/小夜班/大夜班） */
export const SHIFT_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'DAY', label: '白班' },
  { code: 'EVENING', label: '小夜班' },
  { code: 'NIGHT', label: '大夜班' },
];

/** 病区患者资源组：入区登记 / 病区一览 / 患者详情 / 出区移除。 */
export const wardPatients = {
  /** 入区登记（新在区记录落床；床位/护理级别等由表单显式校验后出网）。 */
  register: async (payload: WardPatientRegisterRequest): Promise<WardPatientVO> => {
    const resp = await http.post<WardPatientVO>('/v1/nursing/ward-patients', payload);
    return resp.data;
  },
  /** 病区患者一览（按后端返回序；床位序排序由前端承载，卡墙渲染口径）。 */
  list: async (wardId: string): Promise<WardPatientVO[]> => {
    const resp = await http.get<WardPatientVO[]>('/v1/nursing/ward-patients', {
      params: { wardId },
    });
    return resp.data;
  },
  /** 患者详情（姓名/年龄/病情/过敏/风险/分配/在途任务聚合面）。 */
  detail: async (visitId: string): Promise<WardPatientDetailVO> => {
    const resp = await http.get<WardPatientDetailVO>(`/v1/nursing/ward-patients/${visitId}`);
    return resp.data;
  },
  /** 出区移除（高风险档：必填原因留痕，状态机终态由后端把守）。 */
  remove: async (visitId: string, payload: WardPatientRemoveRequest): Promise<WardPatientVO> => {
    const resp = await http.post<WardPatientVO>(
      `/v1/nursing/ward-patients/${visitId}/remove`,
      payload,
    );
    return resp.data;
  },
};

/** 责任护士分配资源组：班次分配清单 / 新增分配 / 移除分配。 */
export const assignments = {
  /** 班次分配清单（wardId + shiftCode 双参定位）。 */
  list: async (wardId: string, shiftCode: string): Promise<NurseAssignmentVO[]> => {
    const resp = await http.get<NurseAssignmentVO[]>('/v1/nursing/assignments', {
      params: { wardId, shiftCode },
    });
    return resp.data;
  },
  /** 新增分配（PRIMARY 责任患者 / BED 管床两型，必填面由后端校验）。 */
  create: async (payload: NurseAssignmentRequest): Promise<NurseAssignmentVO> => {
    const resp = await http.post<NurseAssignmentVO>('/v1/nursing/assignments', payload);
    return resp.data;
  },
  /** 移除分配（当班撤管）。 */
  remove: async (id: string): Promise<void> => {
    await http.delete(`/v1/nursing/assignments/${id}`);
  },
};

/** 体征资源组：录入 / 患者时序查询 / 病区待复核队列 / 确认 / 驳回。 */
export const vitalSigns = {
  /** 体征录入（体图文书链入口：确认后入体温单，超生理极限 NS-1005 由后端把守）。 */
  record: async (payload: VitalSignRecordRequest): Promise<VitalSignVO> => {
    const resp = await http.post<VitalSignVO>('/v1/nursing/vital-signs', payload);
    return resp.data;
  },
  /** 患者体征时序查询（from/to ISO 时点，详情面板最新体征与体温单取值共用）。 */
  list: async (params: {
    patientId: string;
    from?: string;
    to?: string;
  }): Promise<VitalSignVO[]> => {
    const resp = await http.get<VitalSignVO[]>('/v1/nursing/vital-signs', { params });
    return resp.data;
  },
  /** 病区待复核队列（IoT/一体机双通道来源，复核前不入体温单）。 */
  pendingReview: async (wardId: string): Promise<VitalSignVO[]> => {
    const resp = await http.get<VitalSignVO[]>('/v1/nursing/vital-signs/pending-review', {
      params: { wardId },
    });
    return resp.data;
  },
  /** 确认入体温单（幂等由后端状态机承载）。 */
  confirm: async (id: string): Promise<VitalSignVO> => {
    const resp = await http.post<VitalSignVO>(`/v1/nursing/vital-signs/${id}/confirm`);
    return resp.data;
  },
  /** 驳回（必填原因留痕）。 */
  reject: async (id: string, payload: VitalSignRejectRequest): Promise<VitalSignVO> => {
    const resp = await http.post<VitalSignVO>(`/v1/nursing/vital-signs/${id}/reject`, payload);
    return resp.data;
  },
};

/** 体温单资源组：月页查询 / 特殊事件录入。 */
export const chart = {
  /** 月页查询（month 格式 yyyy-MM；三类条目三段分组返回，值经 vitalRef 关联体征行）。 */
  query: async (visitId: string, month: string): Promise<TemperatureChartVO> => {
    const resp = await http.get<TemperatureChartVO>('/v1/nursing/temperature-charts', {
      params: { visitId, month },
    });
    return resp.data;
  },
  /** 特殊事件录入（入院/手术/分娩/转科/出院/死亡/物理降温/短绌起止/呼吸心跳停止）。 */
  addSpecialEvent: async (visitId: string, payload: SpecialEventRequest): Promise<ChartEntryVO> => {
    const resp = await http.post<ChartEntryVO>(
      `/v1/nursing/temperature-charts/${visitId}/special-events`,
      payload,
    );
    return resp.data;
  },
};

/** 出入量明细资源组：明细录入 / 按日清单。 */
export const ioRecords = {
  /** 出入量明细录入（quantity string 透传零运算；小结链由后端按班聚合）。 */
  create: async (payload: IoRecordCreateRequest): Promise<IoRecordVO> => {
    const resp = await http.post<IoRecordVO>('/v1/nursing/io-records', payload);
    return resp.data;
  },
  /** 按日出入量明细清单（date 过滤可选）。 */
  list: async (params: { visitId: string; date?: string }): Promise<IoRecordVO[]> => {
    const resp = await http.get<IoRecordVO[]>('/v1/nursing/io-records', { params });
    return resp.data;
  },
};

/** 护理记录单资源组：创建 / 提交锁定 / 修订留痕 / 按日清单。 */
export const records = {
  /** 护理记录创建（结构化三段 + 自由文本，DRAFT 态落库）。 */
  create: async (payload: NursingRecordCreateRequest): Promise<NursingRecordVO> => {
    const resp = await http.post<NursingRecordVO>('/v1/nursing/nursing-records', payload);
    return resp.data;
  },
  /** 提交锁定（锁定后修改须走修订链）。 */
  submit: async (recordNo: string): Promise<NursingRecordVO> => {
    const resp = await http.post<NursingRecordVO>(`/v1/nursing/nursing-records/${recordNo}/submit`);
    return resp.data;
  },
  /** 修订（原值留痕，修订链引用由后端承载）。 */
  revise: async (
    recordNo: string,
    payload: NursingRecordReviseRequest,
  ): Promise<NursingRecordVO> => {
    const resp = await http.post<NursingRecordVO>(
      `/v1/nursing/nursing-records/${recordNo}/revise`,
      payload,
    );
    return resp.data;
  },
  /** 按日记录清单（date 过滤可选）。 */
  list: async (params: { visitId: string; date?: string }): Promise<NursingRecordVO[]> => {
    const resp = await http.get<NursingRecordVO[]>('/v1/nursing/nursing-records', { params });
    return resp.data;
  },
};

/** 护理评估资源组：量表定义 / 提交判级 / 历史清单。 */
export const assessments = {
  /** 内置量表定义清单（条目/选项/总分规则，评估表单渲染唯一数据源，前端零内置量表）。 */
  scales: async (): Promise<ScaleDefinitionVO[]> => {
    const resp = await http.get<ScaleDefinitionVO[]>('/v1/nursing/assessment-scales');
    return resp.data;
  },
  /** 提交评估（answers 条目分值映射；总分判级与高危联动由后端承载）。 */
  create: async (payload: NursingAssessmentCreateRequest): Promise<NursingAssessmentVO> => {
    const resp = await http.post<NursingAssessmentVO>('/v1/nursing/assessments', payload);
    return resp.data;
  },
  /** 患者历史评估清单（scaleType 过滤可选）。 */
  list: async (params: { visitId: string; scaleType?: string }): Promise<NursingAssessmentVO[]> => {
    const resp = await http.get<NursingAssessmentVO[]>('/v1/nursing/assessments', { params });
    return resp.data;
  },
};

/** 护理任务资源组：清单 / 完成 / 取消。 */
export const tasks = {
  /** 任务清单（wardId + status/date 过滤；逾期 overdueFlag 为动作标记不改状态）。 */
  list: async (params: {
    wardId: string;
    status?: string;
    date?: string;
  }): Promise<NursingTaskVO[]> => {
    const resp = await http.get<NursingTaskVO[]>('/v1/nursing/tasks', { params });
    return resp.data;
  },
  /** 完成任务（逾期任务仍可完成，M05 Spec §5 状态机口径）。 */
  complete: async (taskNo: string): Promise<NursingTaskVO> => {
    const resp = await http.post<NursingTaskVO>(`/v1/nursing/tasks/${taskNo}/complete`);
    return resp.data;
  },
  /** 取消任务（必填原因留痕）。 */
  cancel: async (taskNo: string, payload: NursingTaskCancelRequest): Promise<NursingTaskVO> => {
    const resp = await http.post<NursingTaskVO>(`/v1/nursing/tasks/${taskNo}/cancel`, payload);
    return resp.data;
  },
};

/** 交接班资源组：生成 / 双签完成 / 按日清单。 */
export const handovers = {
  /** 生成交接班材料（SBAR 自动汇总 + 患者摘要 + 待续事项）。 */
  generate: async (payload: HandoverGenerateRequest): Promise<ShiftHandoverVO> => {
    const resp = await http.post<ShiftHandoverVO>('/v1/nursing/handovers/generate', payload);
    return resp.data;
  },
  /** 完成交接（双签终态；接班护士必填）。 */
  complete: async (
    handoverNo: string,
    payload: HandoverCompleteRequest,
  ): Promise<ShiftHandoverVO> => {
    const resp = await http.post<ShiftHandoverVO>(
      `/v1/nursing/handovers/${handoverNo}/complete`,
      payload,
    );
    return resp.data;
  },
  /** 按日交接班清单（病区切换/刷新后回显当日材料）。 */
  list: async (params: { wardId: string; date?: string }): Promise<ShiftHandoverVO[]> => {
    const resp = await http.get<ShiftHandoverVO[]>('/v1/nursing/handovers', { params });
    return resp.data;
  },
};

/** PDA 资源组：腕带/卡号解析患者摘要 / 巡视打卡。 */
export const pda = {
  /** 患者摘要（脱敏口径：无证件/手机号字段；identifier 为腕带住院号或患者卡号）。 */
  patientSummary: async (identifier: string): Promise<PdaPatientSummaryVO> => {
    const resp = await http.get<PdaPatientSummaryVO>('/v1/nursing/pda/patient-summary', {
      params: { identifier },
    });
    return resp.data;
  },
  /** 巡视打卡（生成巡视任务完成回执，taskNo 回显）。 */
  patrol: async (payload: PdaPatrolRequest): Promise<NursingTaskVO> => {
    const resp = await http.post<NursingTaskVO>('/v1/nursing/pda/patrol', payload);
    return resp.data;
  },
};
