/**
 * 护理域 API（M05 前端面，一域一文件）：病区患者（一览/详情）、责任护士分配、
 * 体征（录入/查询/待复核 confirm/reject）、体温单（月页查询/特殊事件）、出入量明细、
 * 护理记录单（创建/提交/修订）、护理评估（量表定义/提交/历史）、护理任务（列表/完成/取消/
 * 认领/常规模板生成）、交接班（生成/完成/清单）、PDA（患者摘要/巡视打卡/输液拔针/破码放行
 * 双授权）、执行单（工作台清单/闭环追溯/签收/核对/开始/完成/撤销）、在途输注（监测挂接
 * 聚合）、护理不良事件（分页/上报/处理/关闭/退回）。
 * 路径前缀 /v1/nursing/**（baseURL 已含 /api）；雪花 id 与数量金额一律 string 承载
 * （web A.3-6），本域无金额运算面（quantity 透传零运算）。
 * REST 面为后端 Task 1-11 冻结契约；函数按资源分组导出（spec mock 面）。
 * W-34 退役（Task 7）：入区登记/出区移除两端点已随过渡通道退役——床位移除动作归
 * inpatient 出院/转科事件投影，前端不再直调移除端点（本文件零 register/remove 面）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type WardPatientVO = components['schemas']['WardPatientVO'];
export type WardPatientDetailVO = components['schemas']['WardPatientDetailVO'];
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
export type NeedleOutRequest = components['schemas']['NeedleOutRequest'];
export type OverrideCheckRequest = components['schemas']['OverrideCheckRequest'];
export type OrderExecutionVO = components['schemas']['OrderExecutionVO'];
export type OrderExecutionTraceVO = components['schemas']['OrderExecutionTraceVO'];
export type CheckLogVO = components['schemas']['CheckLogVO'];
/** 执行单分页出参（common PageResult 单泛型生成物：content/page/size/total） */
export type OrderExecutionPage = components['schemas']['PageResultOrderExecutionVO'];
export type ExecutionCheckRequest = components['schemas']['CheckRequest'];
export type ExecutionSignReceiveRequest = components['schemas']['SignReceiveRequest'];
export type ExecutionStartRequest = components['schemas']['StartRequest'];
export type ExecutionFinishRequest = components['schemas']['FinishRequest'];
export type ExecutionCancelRequest = components['schemas']['CancelExecutionRequest'];
export type ActiveInfusionVO = components['schemas']['ActiveInfusionVO'];
export type TaskClaimRequest = components['schemas']['TaskClaimRequest'];
export type RoutineTaskGenerateRequest = components['schemas']['RoutineTaskGenerateRequest'];
export type RoutineTaskGenerateVO = components['schemas']['RoutineTaskGenerateVO'];
export type AdverseEventVO = components['schemas']['AdverseEventVO'];
/** 不良事件分页出参（common PageResult 单泛型生成物：content/page/size/total） */
export type AdverseEventPage = components['schemas']['PageResultAdverseEventVO'];
export type AdverseEventReportRequest = components['schemas']['AdverseEventReportRequest'];
export type AdverseEventReturnRequest = components['schemas']['AdverseEventReturnRequest'];
export type AdverseEventHandleRequest = components['schemas']['AdverseEventHandleRequest'];
export type AdverseEventCloseRequest = components['schemas']['AdverseEventCloseRequest'];

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

/** 病情标记选项已随 W-34 退役（conditionTags 字段不可推导自四事件载荷，Task 7 ④项）；
 * 病情角标/危重计数派生面同步退役，勿再以任何词表形态回补。 */

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

/** 执行单状态词表（后端 ExecutionStatus 六值展示映射；COMPLETED/CANCELLED 为终态，
 * 工作台四列看板不承载——列映射见 useExecutions.EXECUTION_COLUMNS） */
export const EXECUTION_STATUS_LABELS: Record<string, string> = {
  CREATED: '待签收',
  SIGNED: '已签收',
  CHECKED: '已核对',
  EXECUTING: '执行中',
  COMPLETED: '已完成',
  CANCELLED: '已撤销',
};

/** 执行类型词表（后端 ExecutionType 二值：GENERIC 通用给药/INFUSION 输液[监测挂接]） */
export const EXECUTION_TYPE_LABELS: Record<string, string> = {
  GENERIC: '通用给药',
  INFUSION: '输液',
};

/** 三向扫码核对方式选项（后端 CheckType 三值：腕带=visitId 匹配/瓶签=袋签码/设备=执行单条码） */
export const CHECK_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'WRISTBAND', label: '腕带' },
  { code: 'BAG_LABEL', label: '瓶签' },
  { code: 'DEVICE', label: '执行单' },
];

/** 不良事件类别选项（后端 AdverseEventCategory 八值冻结词表，扩充属 CF 契约变更） */
export const ADVERSE_CATEGORY_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'MEDICATION_ERROR', label: '用药错误' },
  { code: 'FALL', label: '跌倒坠床' },
  { code: 'PRESSURE_ULCER', label: '压疮' },
  { code: 'TUBE_SLIP', label: '管路滑脱' },
  { code: 'BLOOD_TRANFUSION', label: '输血' },
  { code: 'DEVICE', label: '器械' },
  { code: 'FACILITY', label: '设施' },
  { code: 'OTHER', label: '其他' },
];

/** 严重度分级选项（后端 SeverityClass 四值：I 最重（24h 强制上报时限）→ IV 最轻） */
export const SEVERITY_CLASS_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'I', label: 'I 级（最重）' },
  { code: 'II', label: 'II 级（重）' },
  { code: 'III', label: 'III 级（中）' },
  { code: 'IV', label: 'IV 级（轻）' },
];

/** 严重度等级选项（后端 SeverityGrade 五值 A~E） */
export const SEVERITY_GRADE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'A', label: 'A' },
  { code: 'B', label: 'B' },
  { code: 'C', label: 'C' },
  { code: 'D', label: 'D' },
  { code: 'E', label: 'E' },
];

/** 病区患者资源组：病区一览 / 患者详情（W-34：入区登记与出区移除端点已退役，
 * 床位进出归 inpatient 入院/出院/转科事件投影，前端零直调移除面）。 */
export const wardPatients = {
  /** 病区患者一览（按后端返回序；床位序排序由前端承载，卡墙渲染口径）。 */
  list: async (wardId: string): Promise<WardPatientVO[]> => {
    const resp = await http.get<WardPatientVO[]>('/v1/nursing/ward-patients', {
      params: { wardId },
    });
    return resp.data;
  },
  /** 患者详情（姓名/过敏/风险/分配/在途任务聚合面；gender/age 恒 null 无值不渲染，
   * conditionTags 已随 W-34 退役——Task 7 审查 C2/C3 前端口径）。 */
  detail: async (visitId: string): Promise<WardPatientDetailVO> => {
    const resp = await http.get<WardPatientDetailVO>(`/v1/nursing/ward-patients/${visitId}`);
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

/** 护理任务资源组：清单 / 完成 / 取消 / 认领 / 常规模板生成。 */
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
  /** 认领任务（待执行 → 执行中；assigneeId 留痕由后端承载）。 */
  claim: async (taskNo: string, payload: TaskClaimRequest): Promise<NursingTaskVO> => {
    const resp = await http.post<NursingTaskVO>(`/v1/nursing/tasks/${taskNo}/claim`, payload);
    return resp.data;
  },
  /** 常规模板批量生成当日任务（幂等由后端模板日去重承载，返回生成条数）。 */
  generateRoutine: async (payload: RoutineTaskGenerateRequest): Promise<RoutineTaskGenerateVO> => {
    const resp = await http.post<RoutineTaskGenerateVO>(
      '/v1/nursing/tasks/generate-routine',
      payload,
    );
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

/** PDA 资源组（Task 15 扩 executions 面）：腕带/卡号解析患者摘要 / 巡视打卡 /
 * 输液拔针 / 破码放行双授权。清单与 check/start/finish 复用执行单资源组同族端点。 */
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
  /** 输液拔针（INFUSION 型完成形态：腕带复扫核对+实际输注量→挂接收口+自动入量+双路回签；
   * GENERIC 型完成走执行单资源组 finish 端点，后端型守卫 fail-closed）。 */
  needleOut: async (no: string, payload: NeedleOutRequest): Promise<OrderExecutionVO> => {
    const resp = await http.post<OrderExecutionVO>(
      `/v1/nursing/executions/${no}/needle-out`,
      payload,
    );
    return resp.data;
  },
  /** 破码放行双授权（扫码核对失败后双人授权留痕：override_flag 置位 + OVERRIDE 流水落行；
   * 两授权人不得相同与角色校验归后端把守，前端显式校验前置零出网）。 */
  overrideCheck: async (payload: OverrideCheckRequest): Promise<OrderExecutionVO> => {
    const resp = await http.post<OrderExecutionVO>('/v1/nursing/pda/override-check', payload);
    return resp.data;
  },
};

/** 执行单资源组（FU-M05-04 医嘱执行前端面）：工作台分组清单 / 闭环追溯 / 五环节动作
 * （补签收/三向扫码核对/开始执行/执行完成/撤销）。状态机与时间窗校验归后端把守，
 * 前端按返回态重拉看板；拔针（needle-out）归 Task 15 PDA 住院执行扩展同源面。 */
export const executions = {
  /** 执行工作台分组清单（病区+日期+班次+状态过滤，计划时间升序由后端承载）。 */
  list: async (params: {
    wardId: string;
    date?: string;
    shift?: string;
    status?: string;
    page?: number;
    size?: number;
  }): Promise<OrderExecutionPage> => {
    const resp = await http.get<OrderExecutionPage>('/v1/nursing/executions', { params });
    return resp.data;
  },
  /** 执行单闭环追溯（五环节时点+核对流水+关联告警，详情抽屉时间线数据源）。 */
  trace: async (no: string): Promise<OrderExecutionTraceVO> => {
    const resp = await http.get<OrderExecutionTraceVO>(`/v1/nursing/executions/${no}/trace`);
    return resp.data;
  },
  /** 人工补签收（非药品类；药品类经摆药签收衔接自动签收）。 */
  signReceive: async (
    no: string,
    payload: ExecutionSignReceiveRequest,
  ): Promise<OrderExecutionVO> => {
    const resp = await http.post<OrderExecutionVO>(
      `/v1/nursing/executions/${no}/sign-receive`,
      payload,
    );
    return resp.data;
  },
  /** 三向扫码核对（腕带/瓶签/设备单维核对，PASS→CHECKED，FAIL 留痕拒绝）。 */
  check: async (no: string, payload: ExecutionCheckRequest): Promise<OrderExecutionVO> => {
    const resp = await http.post<OrderExecutionVO>(`/v1/nursing/executions/${no}/check`, payload);
    return resp.data;
  },
  /** 开始执行（CHECKED→EXECUTING，时间窗外未破码拒绝归后端）。 */
  start: async (no: string, payload: ExecutionStartRequest): Promise<OrderExecutionVO> => {
    const resp = await http.post<OrderExecutionVO>(`/v1/nursing/executions/${no}/start`, payload);
    return resp.data;
  },
  /** 执行完成（EXECUTING→COMPLETED+双路回签 M04；输注类完成归 PDA 拔针面）。 */
  finish: async (no: string, payload: ExecutionFinishRequest): Promise<OrderExecutionVO> => {
    const resp = await http.post<OrderExecutionVO>(`/v1/nursing/executions/${no}/finish`, payload);
    return resp.data;
  },
  /** 撤销（未执行三态常规撤销，必填原因留痕；EXECUTING 中断归输液面不在本入口）。 */
  cancel: async (no: string, payload: ExecutionCancelRequest): Promise<OrderExecutionVO> => {
    const resp = await http.post<OrderExecutionVO>(`/v1/nursing/executions/${no}/cancel`, payload);
    return resp.data;
  },
};

/** 在途输注资源组（FU-M05-06 输液闭环前端面）：病区在途清单（监测挂接聚合）。 */
export const infusions = {
  /** 病区在途输注清单（开始时点升序；输液遥测条按 patientId 对齐的护理侧数据源）。 */
  active: async (wardId: string): Promise<ActiveInfusionVO[]> => {
    const resp = await http.get<ActiveInfusionVO[]>('/v1/nursing/infusions/active', {
      params: { wardId },
    });
    return resp.data;
  },
};

/** 护理不良事件资源组（FU-M05-09）：分页清单 / 上报 / 处理 / RCA 关闭 / 退回。
 * 非惩罚通道口径：匿名上报开关透传，超时上报只留痕不拒绝（归后端承载）。 */
export const adverseEvents = {
  /** 分页清单（category/wardId/status/date 筛选均可空；page 0 基透传）。 */
  list: async (params: {
    category?: string;
    wardId?: string;
    status?: string;
    date?: string;
    page?: number;
    size?: number;
  }): Promise<AdverseEventPage> => {
    const resp = await http.get<AdverseEventPage>('/v1/nursing/adverse-events', { params });
    return resp.data;
  },
  /** 上报（类别/分级/等级/病区/时点/经过必填归后端校验，前端显式校验前置零出网）。 */
  report: async (payload: AdverseEventReportRequest): Promise<AdverseEventVO> => {
    const resp = await http.post<AdverseEventVO>('/v1/nursing/adverse-events', payload);
    return resp.data;
  },
  /** 处理（REPORTED→HANDLING；handlerId 操作留痕）。 */
  handle: async (no: string, payload: AdverseEventHandleRequest): Promise<AdverseEventVO> => {
    const resp = await http.post<AdverseEventVO>(
      `/v1/nursing/adverse-events/${no}/handle`,
      payload,
    );
    return resp.data;
  },
  /** RCA 关闭（HANDLING→CLOSED 终态；rcaNote/correctiveAction 留痕）。 */
  close: async (no: string, payload: AdverseEventCloseRequest): Promise<AdverseEventVO> => {
    const resp = await http.post<AdverseEventVO>(`/v1/nursing/adverse-events/${no}/close`, payload);
    return resp.data;
  },
  /** 退回（上报信息不全时退回补报；必填原因 + returnerId 留痕）。 */
  return: async (no: string, payload: AdverseEventReturnRequest): Promise<AdverseEventVO> => {
    const resp = await http.post<AdverseEventVO>(
      `/v1/nursing/adverse-events/${no}/return`,
      payload,
    );
    return resp.data;
  },
};
