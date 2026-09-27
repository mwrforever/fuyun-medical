/**
 * 病区视图域 API（M16 前端面，一域一文件）：病区呼叫（分页/新建/应答/处理/完成/转接/取消）+
 * 冷链台账（档案分页/新建/详情/记录查询/巡检与告警处置登记）+ 输液看板（病区快照）+
 * 体征看板（病区快照）。路径前缀 /v1/ward/**（baseURL 已含 /api）；雪花 id 与 long 后端经
 * Jackson 全局以字符串输出（backend A.3-8），前端类型一律 string 承载（web A.3-6）。
 * REST 面为后端 Task 12/13 冻结契约（生成物唯一来源）；函数按资源分组导出（spec mock 面）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type WardCallVO = components['schemas']['WardCallVO'];
export type CreateWardCallRequest = components['schemas']['CreateWardCallRequest'];
export type WardCallQueryRequest = components['schemas']['WardCallQueryRequest'];
export type CompleteWardCallRequest = components['schemas']['CompleteWardCallRequest'];
export type ColdChainArchiveVO = components['schemas']['ColdChainArchiveVO'];
export type SaveColdChainArchiveRequest = components['schemas']['SaveColdChainArchiveRequest'];
export type ColdChainRecordVO = components['schemas']['ColdChainRecordVO'];
export type RegisterColdChainRecordRequest =
  components['schemas']['RegisterColdChainRecordRequest'];
export type InfusionBoardVO = components['schemas']['InfusionBoardVO'];
export type InfusionBoardDeviceVO = components['schemas']['InfusionBoardDeviceVO'];
export type VitalBoardVO = components['schemas']['VitalBoardVO'];

/** 呼叫分页出参（common PageResult 单泛型生成物：content/page/size/total） */
export type WardCallPage = components['schemas']['PageResultWardCallVO'];
/** 冷链档案分页出参 */
export type ColdChainArchivePage = components['schemas']['PageResultColdChainArchiveVO'];

/** 呼叫状态六值中文词表（CREATED 待应答/ANSWERED 已应答/IN_PROGRESS 处理中/COMPLETED 已完成/
 * TRANSFERRED 已转接/CANCELLED 已取消——呼叫闭环生命周期，工作台列表徽标与筛选共用） */
export const CALL_STATUS_LABELS: Record<string, string> = {
  CREATED: '待应答',
  ANSWERED: '已应答',
  IN_PROGRESS: '处理中',
  COMPLETED: '已完成',
  TRANSFERRED: '已转接',
  CANCELLED: '已取消',
};

/** 呼叫类型四值中文词表（NORMAL 普通/EMERGENCY 紧急/INFUSION 输液/SERVICE 服务） */
export const CALL_TYPE_LABELS: Record<string, string> = {
  NORMAL: '普通呼叫',
  EMERGENCY: '紧急呼叫',
  INFUSION: '输液呼叫',
  SERVICE: '服务请求',
};

/** 呼叫来源五值中文词表（与护理域 source 词表同源：床头分机/卫生间/患者手环/护士 PDA/物联网设备） */
export const CALL_SOURCE_LABELS: Record<string, string> = {
  BEDSIDE: '床头分机',
  BRROOM: '卫生间',
  PATIENT_PAD: '患者手环',
  NURSE_PAD: '护士 PDA',
  IOT: '物联网设备',
};

/** 冷链用途四值中文词表（VACCINE 疫苗/BLOOD 血液/REAGENT 试剂/PHARMA 药品） */
export const COLD_PURPOSE_LABELS: Record<string, string> = {
  VACCINE: '疫苗',
  BLOOD: '血液',
  REAGENT: '试剂',
  PHARMA: '药品',
};

/** 冷链温区四值中文词表（FREEZE 冷冻/COOL 冷藏/SHELDED 阴凉/NORMAL 常温；SHELDED 为
 * 后端冻结枚举原样承载） */
export const TEMP_RANGE_LABELS: Record<string, string> = {
  FREEZE: '冷冻',
  COOL: '冷藏',
  SHELDED: '阴凉',
  NORMAL: '常温',
};

/** 冷链记录类型三值中文词表（INSPECTION 巡检/ALARM_HANDLE 告警处置/DEVIATION 偏差记录） */
export const COLD_RECORD_TYPE_LABELS: Record<string, string> = {
  INSPECTION: '巡检',
  ALARM_HANDLE: '告警处置',
  DEVIATION: '偏差记录',
};

/** 输液告警档位四值中文词表（NONE 正常/YELLOW 黄档 ≤15ml/ORANGE 橙档 ≤10ml/RED 红档 ≤5ml，
 * 档位判定归后端 InfusionBoardDeviceVO.mapAlertLevel，前端只按返回档位着色） */
export const INFUSION_ALERT_LABELS: Record<string, string> = {
  NONE: '正常',
  YELLOW: '黄档预警',
  ORANGE: '橙档告急',
  RED: '红档危急',
};

/** 病区选项（冻结 REST 面无病区清单端点，P2 以演示病区种子前端常量承载；病区标识为纯数字
 * 字符串——后端 Long 经 Jackson 字符串化，与 WS 主题 /topic/iot/telemetry、alarm 病区段同源） */
export const WARD_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: '1001', label: '1001 演示病区' },
];

/** 病区呼叫资源组：分页 / 新建 / 应答 / 处理 / 完成 / 转接 / 取消（动作端点状态机流转
 * 单点归后端把守，前端按返回态刷新列表）。 */
export const wardCalls = {
  /** 呼叫分页（status 空=全部六态；wardId 可空不过滤）。 */
  page: async (params: WardCallQueryRequest): Promise<WardCallPage> => {
    const resp = await http.get<WardCallPage>('/v1/ward/ward-calls', { params });
    return resp.data;
  },
  /** 呼叫新建（床旁/手环等来源登记入口；CREATED 初始态由后端承载）。 */
  create: async (payload: CreateWardCallRequest): Promise<WardCallVO> => {
    const resp = await http.post<WardCallVO>('/v1/ward/ward-calls', payload);
    return resp.data;
  },
  /** 应答（CREATED→ANSWERED；应答人取会话，由后端承载）。 */
  answer: async (callNo: string): Promise<WardCallVO> => {
    const resp = await http.post<WardCallVO>(`/v1/ward/ward-calls/${callNo}/answer`);
    return resp.data;
  },
  /** 处理（ANSWERED→IN_PROGRESS，开始到场处置）。 */
  progress: async (callNo: string): Promise<WardCallVO> => {
    const resp = await http.post<WardCallVO>(`/v1/ward/ward-calls/${callNo}/progress`);
    return resp.data;
  },
  /** 完成（IN_PROGRESS→COMPLETED；resultSummary 处置结果强制由前端显式校验+后端兜底）。 */
  complete: async (callNo: string, payload: CompleteWardCallRequest): Promise<WardCallVO> => {
    const resp = await http.post<WardCallVO>(`/v1/ward/ward-calls/${callNo}/complete`, payload);
    return resp.data;
  },
  /** 转接（转接目标链路归后端路由引擎；转接后状态置 TRANSFERRED）。 */
  transfer: async (callNo: string): Promise<WardCallVO> => {
    const resp = await http.post<WardCallVO>(`/v1/ward/ward-calls/${callNo}/transfer`);
    return resp.data;
  },
  /** 取消（CREATED/ANSWERED 等非终态可取消，终态拒绝归后端把守）。 */
  cancel: async (callNo: string): Promise<WardCallVO> => {
    const resp = await http.post<WardCallVO>(`/v1/ward/ward-calls/${callNo}/cancel`);
    return resp.data;
  },
};

/** 冷链台账资源组：档案分页 / 新建 / 详情 / 记录查询 / 巡检与告警处置登记。 */
export const coldChain = {
  /** 档案分页（purpose 空=全部四用途；verifyDueAt 过期由后端 overdue 承载）。 */
  page: async (params: {
    purpose?: string;
    page?: number;
    size?: number;
  }): Promise<ColdChainArchivePage> => {
    const resp = await http.get<ColdChainArchivePage>('/v1/ward/cold-chain/archives', { params });
    return resp.data;
  },
  /** 档案新建（疫苗/血液等用途登记；设备标识须为已注册冷链设备，校验归后端）。 */
  create: async (payload: SaveColdChainArchiveRequest): Promise<ColdChainArchiveVO> => {
    const resp = await http.post<ColdChainArchiveVO>('/v1/ward/cold-chain/archives', payload);
    return resp.data;
  },
  /** 档案详情（详情抽屉回显；含 overdue 校验到期标记）。 */
  detail: async (archiveNo: string): Promise<ColdChainArchiveVO> => {
    const resp = await http.get<ColdChainArchiveVO>(`/v1/ward/cold-chain/archives/${archiveNo}`);
    return resp.data;
  },
  /** 记录查询（指定档案的巡检/告警处置/偏差记录全集，按返回序直出）。 */
  records: async (archiveNo: string): Promise<ColdChainRecordVO[]> => {
    const resp = await http.get<ColdChainRecordVO[]>(
      `/v1/ward/cold-chain/archives/${archiveNo}/records`,
    );
    return resp.data;
  },
  /** 记录登记（巡检 INSPECTION/告警处置 ALARM_HANDLE——双人复核 secondOperator 强制、
   * 偏差 DEVIATION；登记人取会话由后端承载）。 */
  registerRecord: async (
    archiveNo: string,
    payload: RegisterColdChainRecordRequest,
  ): Promise<ColdChainRecordVO> => {
    const resp = await http.post<ColdChainRecordVO>(
      `/v1/ward/cold-chain/archives/${archiveNo}/records`,
      payload,
    );
    return resp.data;
  },
};

/** 输液看板资源组：病区快照（余量/滴速最新值与告警档位，WS 增量的 REST 全量兜底面）。 */
export const infusionBoard = {
  /** 病区输液看板快照（余量 latest/滴速 latest/alertLevel 档位由后端判定；无遥测设备余量为 null）。 */
  byWard: async (wardId: string): Promise<InfusionBoardVO> => {
    const resp = await http.get<InfusionBoardVO>(`/v1/ward/infusion-board/${wardId}`);
    return resp.data;
  },
};

/** 体征看板资源组：病区快照（在床体征异常清单——P2 病区视图只落 API 面，看板页随后续任务）。 */
export const vitalBoard = {
  /** 病区体征看板快照（presenceMetric 在床体征指标 + anomalies 异常清单 + note 注记）。 */
  byWard: async (wardId: string): Promise<VitalBoardVO> => {
    const resp = await http.get<VitalBoardVO>(`/v1/ward/vital-board/${wardId}`);
    return resp.data;
  },
};
