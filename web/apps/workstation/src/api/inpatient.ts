/**
 * 住院域 API（M04 前端面，一域一文件）：入院登记（住院证建单/候床队列/预约/作废/登记确认/
 * 入科确认/欠费清单）+ 床位管理（床位图五态/预占/占床/释放/消毒完成/维修/恢复）+
 * 转科转床（四阶段编排/同病区轻量转床）+ 医嘱开立与闭环追溯（开立/分页/追溯时间线）+
 * 医嘱转抄（工作台列表/批量核对）+ 出院管理（申请/取消/清理预审/离院确认）。
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
/** 医嘱头出参（分页行与开立返回共用） */
export type MedicalOrderVO = components['schemas']['MedicalOrderVO'];
/** 医嘱详情出参（头 + 明细行全集） */
export type OrderDetailVO = components['schemas']['OrderDetailVO'];
/** 医嘱明细行出参（详情渲染面；quantity 为 JSON number——BigDecimal 非 Long 序列化豁免面） */
export type OrderItemVO = components['schemas']['OrderItemVO'];
/** 闭环追溯出参（五环节 entries 时间线） */
export type OrderTraceVO = components['schemas']['OrderTraceVO'];
/** 追溯环节行（stage=ORDERED/AUDIT/TRANSFER/PLAN/STATUS） */
export type TraceEntry = components['schemas']['TraceEntry'];
/** 转抄工作台行（含 highRisk 高危双人核对强制面标记） */
export type TransferWorklistVO = components['schemas']['TransferWorklistVO'];
/** 批量转抄核对入参（生成物在位：orderNos/transferNurseId/conclusion/secondCheckerId） */
export type TransferCheckRequest = components['schemas']['TransferCheckRequest'];
/** 医嘱分页出参 */
export type OrderPage = components['schemas']['PageResultMedicalOrderVO'];
/** 待转抄分页出参 */
export type TransferWorklistPage = components['schemas']['PageResultTransferWorklistVO'];
/** 出院申请单出参（status=REQUESTED/READY/BLOCKED/COMPLETED/CANCELLED） */
export type DischargeRequestVO = components['schemas']['DischargeRequestVO'];
/** 出院申请入参（生成物在位：expectDischargeAt date-time + dischargeWay 病案首页代码） */
export type DischargeRequestCreate = components['schemas']['DischargeRequestCreate'];
/** 离院确认入参（随访三参数可选，缺省 7 日/电话/「出院随访」由后端承载） */
export type DischargeConfirmRequest = components['schemas']['DischargeConfirmRequest'];
/** 清理与预审结果出参（清理三清单计数+追踪清单+欠费额+结算标记+挂账凭证） */
export type ClearanceVO = components['schemas']['ClearanceVO'];
/** 追踪清单行（orderNo/orderClass/status——离院前置清理处置面） */
export type TrackedOrderVO = components['schemas']['TrackedOrderVO'];

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

/** 医嘱类型九值词表（V904 medical_order.order_type 列注释同源；开立下拉与列表类型列共用） */
export const ORDER_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'DRUG', label: '药品' },
  { code: 'LAB', label: '检验' },
  { code: 'EXAM', label: '检查' },
  { code: 'SURGERY', label: '手术' },
  { code: 'BLOOD', label: '用血' },
  { code: 'NURSING', label: '护理' },
  { code: 'DIET', label: '膳食' },
  { code: 'CONSULT', label: '会诊' },
  { code: 'DISCHARGE_MED', label: '出院带药' },
];

/** 医嘱分类两值词表（LONG 长期携频次/STAT 临时不携） */
export const ORDER_CLASS_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'LONG', label: '长期' },
  { code: 'STAT', label: '临时' },
];

/** 医嘱状态八值中文词表（V904 status 列注释同源；列表状态列共用） */
export const ORDER_STATUS_LABELS: Record<string, string> = {
  CREATED: '已开立待审',
  AUDITED: '审核通过',
  AUDIT_REJECTED: '审核驳回',
  TRANSFERRED: '已转抄',
  EXECUTING: '执行中',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
  STOPPED: '已停嘱',
};

/** 用药类医嘱类型集合（审核链口径：DRUG 与 DISCHARGE_MED 停留 CREATED 待药师审，
 * 与后端 OrderType.isMedication 同源；保存成功后按此提示「待药师审」） */
export const MEDICATION_ORDER_TYPES: ReadonlySet<string> = new Set(['DRUG', 'DISCHARGE_MED']);

/** 用药频次词表（V904 order_frequency 七种子 qd/bid/tid/qid/qn/prn/st 同源；冻结 REST 面
 * 无频次字典 GET 端点，前端常量承载——WARD_OPTIONS 同款先例，字典端点就位后换接口源） */
export const ORDER_FREQUENCY_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'qd', label: 'qd 每日一次' },
  { code: 'bid', label: 'bid 每日两次' },
  { code: 'tid', label: 'tid 每日三次' },
  { code: 'qid', label: 'qid 每日四次' },
  { code: 'qn', label: 'qn 每晚一次' },
  { code: 'prn', label: 'prn 必要时（嘱托）' },
  { code: 'st', label: 'st 立即' },
];

/** 离院方式词表（后端 DischargeWay 六值病案首页代码：数字字符存储） */
export const DISCHARGE_WAY_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: '1', label: '医嘱离院' },
  { code: '2', label: '医嘱转院' },
  { code: '3', label: '转社区/乡镇卫生院' },
  { code: '4', label: '非医嘱离院' },
  { code: '5', label: '死亡' },
  { code: '9', label: '其他' },
];

/** 出院申请状态中文词表（后端 DischargeRequestStatus 五值；tab 与行状态列共用） */
export const DISCHARGE_STATUS_LABELS: Record<string, string> = {
  REQUESTED: '预审中',
  READY: '待离院确认',
  BLOCKED: '挂账审批中',
  COMPLETED: '已离院',
  CANCELLED: '已取消',
};

/** 闭环追溯五环节中文词表（后端 OrderPlanServiceImpl stage 常量字面量同源；
 * STATUS 环节承载含停止在内的全部状态迁移行） */
export const TRACE_STAGE_LABELS: Record<string, string> = {
  ORDERED: '开立',
  AUDIT: '审核',
  TRANSFER: '转抄',
  PLAN: '执行',
  STATUS: '状态迁移',
};

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

/**
 * 医嘱开立入参（住院侧运行时契约：后端 inpatient OrderCreateRequest Java record——头六字段
 * + 富明细行）。生成物「OrderCreateRequest/OrderItemRequest」被门诊同名 DTO 碰撞覆盖
 * （Springdoc 简单名冲突：同名 schema 后注册者胜，生成物仅存门诊 {orderType,items} 形状），
 * 按生成物出网将被住院侧 bean 校验 400 拒绝——此处按后端运行时实况声明（GC30 以实况为准），
 * 后端修复同名 DTO 后删除本别名回归生成物（PR 描述登记）。
 */
export interface OrderCreatePayload {
  /** 医嘱类型（ORDER_TYPE_OPTIONS 九值词表） */
  orderType: string;
  /** 医嘱分类（LONG 长期/STAT 临时；LONG 须携 freqCode，缺频次后端拒 IP-1011） */
  orderClass: string;
  /** 备用嘱（嘱托）标记——仅 LONG 可 true（STAT+standby 后端拒 IP-1022），缺省 false */
  standbyFlag?: boolean;
  /** 频次编码（ORDER_FREQUENCY_OPTIONS 七值；LONG 必填/STAT 缺省不传） */
  freqCode?: string;
  /** 明细行列表（≥1 行；多行共用服务层回填组号=成组医嘱） */
  items: OrderItemPayload[];
}

/** 医嘱开立明细行入参（后端 OrderItemRequest record 必需字段对齐+可选字段按 UI 面裁剪；行序号由服务层按列表序生成） */
export interface OrderItemPayload {
  /** 行项目类型（与头 orderType 同词表，服务层校验一致性） */
  itemType: string;
  /** 项目编码（药品/检验等项目字典编码） */
  itemCode: string;
  /** 项目名称（名称快照誊写源，药品通用名非敏感项） */
  itemName: string;
  /** 剂量（数值字符串如 0.5）——药品行必填（服务层拒 IP-1011） */
  dosage?: string;
  /** 剂量单位（如 g/ml）——药品行必填（服务层拒 IP-1011） */
  dosageUnit?: string;
  /** 给药途径（M01 medication.route 字典 code）——药品行必填（服务层拒 IP-1011） */
  route?: string;
  /** 滴速（如 40 滴/分）——静滴类可空 */
  dripRate?: string;
  /** 数量（正数；JSON number——BigDecimal 非 Long 全局字符串化豁免面） */
  quantity: number;
}

/** 医嘱资源组：开立（四层校验→CREATED→审核链收口）/就诊医嘱分页/闭环追溯。 */
export const orders = {
  /** 医嘱开立（红线方法：执业授权/过敏/明细频次/嘱托限定校验与状态迁移全归后端；
   * 用药类返回 status=CREATED 语义=「待药师审」）。 */
  create: async (visitId: string, payload: OrderCreatePayload): Promise<MedicalOrderVO> => {
    const resp = await http.post<MedicalOrderVO>(`/v1/inpatient/visits/${visitId}/orders`, payload);
    return resp.data;
  },
  /** 就诊医嘱分页（开立时间倒序；class 可空=全部分类）。 */
  list: async (params: {
    visitId: string;
    class?: string;
    page: number;
    size: number;
  }): Promise<OrderPage> => {
    const resp = await http.get<OrderPage>('/v1/inpatient/orders', { params });
    return resp.data;
  },
  /** 闭环追溯（开立→审核→转抄→计划执行→状态迁移五环节 entries 时间线，发生时点升序）。 */
  trace: async (orderNo: string): Promise<OrderTraceVO> => {
    const resp = await http.get<OrderTraceVO>(`/v1/inpatient/orders/${orderNo}/trace`);
    return resp.data;
  },
};

/** 转抄资源组：工作台待转抄列表 / 批量核对（双人核对→TRANSFERRED+临时单次计划）。 */
export const transferWorklist = {
  /** 待转抄列表（病区在院就诊 AUDITED 医嘱聚合，开立时间倒序；shift 可空=全部班次；
   * highRisk=true 行高危强制第二核对人——后端 IP-1016 把守）。 */
  list: async (params: {
    wardId: string;
    shift?: string;
    page: number;
    size: number;
  }): Promise<TransferWorklistPage> => {
    const resp = await http.get<TransferWorklistPage>('/v1/inpatient/transfer-worklist', {
      params,
    });
    return resp.data;
  },
  /** 批量转抄核对（整批单事务；高危缺第二核对人拒 IP-1016，已转抄幂等跳过）。 */
  check: async (payload: TransferCheckRequest): Promise<void> => {
    await http.post('/v1/inpatient/orders/transfer-check', payload);
  },
};

/** 出院管理资源组：申请（清理+预审单事务）/取消/清理预审结果/离院确认（双条件放行）。 */
export const discharge = {
  /** 出院申请（「预出院/明日出院」模式；BLOCKED 时出参附欠费额快照——走 M13 挂账审批）。 */
  create: async (visitId: string, payload: DischargeRequestCreate): Promise<DischargeRequestVO> => {
    const resp = await http.post<DischargeRequestVO>(
      `/v1/inpatient/visits/${visitId}/discharge-request`,
      payload,
    );
    return resp.data;
  },
  /** 取消出院申请（仅 REQUESTED 态；visit 回 ADMITTED，医嘱不复活）。 */
  cancel: async (no: string): Promise<DischargeRequestVO> => {
    const resp = await http.post<DischargeRequestVO>(
      `/v1/inpatient/discharge-requests/${no}/cancel`,
    );
    return resp.data;
  },
  /** 清理与预审结果（清理三清单计数+追踪清单+欠费额+结算标记+挂账凭证——人工处置取数面）。 */
  clearance: async (no: string): Promise<ClearanceVO> => {
    const resp = await http.get<ClearanceVO>(`/v1/inpatient/discharge-requests/${no}/clearance`);
    return resp.data;
  },
  /** 离院确认（GC19 前置：预审 READY+结算完成双条件后端把守；联动床位消毒/带药放行/随访）。 */
  confirm: async (no: string, payload: DischargeConfirmRequest): Promise<DischargeRequestVO> => {
    const resp = await http.post<DischargeRequestVO>(
      `/v1/inpatient/discharge-requests/${no}/confirm`,
      payload,
    );
    return resp.data;
  },
};
