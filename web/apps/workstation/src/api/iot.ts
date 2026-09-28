/**
 * 物联网域 API（M14/M16 前端面，一域一文件）：产品与物模型（分页/上架/物模型同步/术语映射/
 * 命令安全等级登记）+ 指标字典（分页/新增）+ 设备（分页/注册/影子/停用/凭证重置）+
 * 设备患者绑定（分页/绑定/解绑）+ 告警规则（列表/新建/修改/删除/模拟回放）+ 告警
 * （分页/确认/关闭）+ 命令（挑战确认/下发/日志分页）+ 联动规则与执行日志（CRUD/重试）+
 * 数据质量（统计/利用率）+ 消费监控（积压）+ 消费错误（分页/重放/放弃）+ 遥测曲线（series）。
 * 路径前缀 /v1/iot/**（baseURL 已含 /api）；雪花 id 与 long 后端经
 * Jackson 全局以字符串输出（backend A.3-8），前端类型一律 string 承载（web A.3-6）。
 * REST 面为后端 Task 2-11 冻结契约（生成物唯一来源）；函数按资源分组导出（spec mock 面）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type ProductVO = components['schemas']['ProductVO'];
export type CreateProductRequest = components['schemas']['CreateProductRequest'];
export type ProductQueryRequest = components['schemas']['ProductQueryRequest'];
export type MetricDictVO = components['schemas']['MetricDictVO'];
export type CreateMetricRequest = components['schemas']['CreateMetricRequest'];
export type DeviceVO = components['schemas']['DeviceVO'];
export type DeviceRegisterRequest = components['schemas']['DeviceRegisterRequest'];
export type DeviceQueryRequest = components['schemas']['DeviceQueryRequest'];
export type DeviceShadowVO = components['schemas']['DeviceShadowVO'];
export type DeviceCredentialResetVO = components['schemas']['DeviceCredentialResetVO'];
export type BindingVO = components['schemas']['BindingVO'];
export type BindDeviceRequest = components['schemas']['BindDeviceRequest'];
export type UnbindDeviceRequest = components['schemas']['UnbindDeviceRequest'];
export type BindingQueryRequest = components['schemas']['BindingQueryRequest'];
export type AlarmRuleVO = components['schemas']['AlarmRuleVO'];
export type SaveAlarmRuleRequest = components['schemas']['SaveAlarmRuleRequest'];
export type SimulateAlarmRequest = components['schemas']['SimulateAlarmRequest'];
export type SimulateResultVO = components['schemas']['SimulateResultVO'];
export type AlarmVO = components['schemas']['AlarmVO'];
export type CloseAlarmRequest = components['schemas']['CloseAlarmRequest'];
export type AlarmQueryRequest = components['schemas']['AlarmQueryRequest'];
/** 映射编辑行（PUT 整组替换语义：propertyName→metricCode 归一映射） */
export type MappingItem = components['schemas']['MappingItem'];
export type MetricMappingVO = components['schemas']['MetricMappingVO'];
/** 命令登记行（safetyLevel 安全级/治疗级——FU-M14-09 白名单数据源） */
export type CommandItem = components['schemas']['CommandItem'];
export type CommandVO = components['schemas']['CommandVO'];
/** 命令下发/挑战两步（P2 PR-2 Task 8 冻结契约：confirm-challenge 签发 challengeId 后凭其下发） */
export type ConfirmChallengeRequest = components['schemas']['ConfirmChallengeRequest'];
export type ConfirmChallengeVO = components['schemas']['ConfirmChallengeVO'];
export type IssueCommandRequest = components['schemas']['IssueCommandRequest'];
export type CommandLogVO = components['schemas']['CommandLogVO'];
export type CommandQueryRequest = components['schemas']['CommandQueryRequest'];
/** 联动规则与执行日志（P2 PR-2 Task 9/10 冻结契约） */
export type SaveLinkageRuleRequest = components['schemas']['SaveLinkageRuleRequest'];
export type LinkageRuleVO = components['schemas']['LinkageRuleVO'];
export type LinkageLogVO = components['schemas']['LinkageLogVO'];
export type LinkageLogQueryRequest = components['schemas']['LinkageLogQueryRequest'];
/** 数据质量统计（质量/利用率共用查询入参）与消费积压、消费错误（P2 PR-2 Task 10/12 契约） */
export type QualityStatQueryRequest = components['schemas']['QualityStatQueryRequest'];
export type DataQualityStatVO = components['schemas']['DataQualityStatVO'];
export type ConsumerStatVO = components['schemas']['ConsumerStatVO'];
export type ConsumeErrorVO = components['schemas']['ConsumeErrorVO'];
export type ConsumeErrorQueryRequest = components['schemas']['ConsumeErrorQueryRequest'];
export type AbandonConsumeErrorRequest = components['schemas']['AbandonConsumeErrorRequest'];
/** 遥测曲线查询（冷链温度曲线轻量渲染消费；scope=device/patient/ward 三维度路由） */
export type TelemetrySeriesRequest = components['schemas']['TelemetrySeriesRequest'];
export type TelemetryPoint = components['schemas']['TelemetryPoint'];

/** 产品分页出参（common PageResult 单泛型生成物：content/page/size/total） */
export type ProductPage = components['schemas']['PageResultProductVO'];
/** 设备分页出参 */
export type DevicePage = components['schemas']['PageResultDeviceVO'];
/** 绑定分页出参 */
export type BindingPage = components['schemas']['PageResultBindingVO'];
/** 告警分页出参 */
export type AlarmPage = components['schemas']['PageResultAlarmVO'];
/** 命令日志分页出参 */
export type CommandLogPage = components['schemas']['PageResultCommandLogVO'];
/** 联动执行日志分页出参 */
export type LinkageLogPage = components['schemas']['PageResultLinkageLogVO'];
/** 数据质量统计分页出参（质量统计表与设备利用率共用出参形态） */
export type QualityStatPage = components['schemas']['PageResultDataQualityStatVO'];
/** 消费错误分页出参 */
export type ConsumeErrorPage = components['schemas']['PageResultConsumeErrorVO'];
/** 物模型同步状态三值词表（SYNCING 同步中/SYNCED 已同步/MISMATCH 失配） */
export type ProductSyncStatus = ProductVO['syncStatus'];

/** 物模型同步状态中文词表（产品列表同步状态徽标共用） */
export const PRODUCT_SYNC_STATUS_LABELS: Record<string, string> = {
  SYNCING: '同步中',
  SYNCED: '已同步',
  MISMATCH: '失配',
};

/** 设备状态五态中文词表（后端 DeviceStatus 五值；列表徽标与筛选共用） */
export const DEVICE_STATUS_LABELS: Record<string, string> = {
  INACTIVE: '未激活',
  ONLINE: '在线',
  OFFLINE: '离线',
  ABNORMAL: '异常',
  DISABLED: '已停用',
};

/** 绑定状态三值中文词表（BOUND 绑定中/UNBINDING 解绑中/UNBOUND 已解绑） */
export const BINDING_STATUS_LABELS: Record<string, string> = {
  BOUND: '绑定中',
  UNBINDING: '解绑中',
  UNBOUND: '已解绑',
};

/** 绑定模式两值中文词表（FIXED 固定式=设备↔床位/MOBILE 移动式=设备↔患者直绑） */
export const BIND_TYPE_LABELS: Record<string, string> = {
  FIXED: '固定式',
  MOBILE: '移动式',
};

/** 告警规则类型三类源中文词表（设备报警透传/平台阈值/离线——FU-M14-08 三类规则源） */
export const RULE_TYPE_LABELS: Record<string, string> = {
  DEVICE_ALARM: '设备报警透传',
  THRESHOLD: '平台阈值',
  OFFLINE: '离线',
};

/** 告警等级三值中文词表（INFO/WARNING/CRITICAL——分级通知策略口径） */
export const ALARM_LEVEL_LABELS: Record<string, string> = {
  INFO: '提示',
  WARNING: '警告',
  CRITICAL: '危急',
};

/** 告警状态三值中文词表（ACTIVE/ACKNOWLEDGED/CLOSED——闭环生命周期） */
export const ALARM_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '活跃',
  ACKNOWLEDGED: '已确认',
  CLOSED: '已关闭',
};

/** 命令下发状态五值中文词表（ISSUED 已下发/DELIVERED 已送达/SUCCESS 成功/FAILED 失败/
 * TIMEOUT 超时——命令日志状态徽标共用） */
export const COMMAND_STATUS_LABELS: Record<string, string> = {
  ISSUED: '已下发',
  DELIVERED: '已送达',
  SUCCESS: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
};

/** 联动触发源三值中文词表（ALARM_TRIGGERED 告警触发/TELEMETRY_ANOMALY 遥测异常/
 * DEVICE_STATUS 设备状态——规则 CRUD 与执行日志筛选共用） */
export const TRIGGER_SOURCE_LABELS: Record<string, string> = {
  ALARM_TRIGGERED: '告警触发',
  TELEMETRY_ANOMALY: '遥测异常',
  DEVICE_STATUS: '设备状态',
};

/** 联动动作类型五值中文词表（NOTIFY 站内通知/M01_NOTIFY M01 通知/CALL_TRANSFER 呼叫转接/
 * NURSING_TASK 护理任务/WARD_BROADCAST 病区播报） */
export const ACTION_TYPE_LABELS: Record<string, string> = {
  NOTIFY: '站内通知',
  M01_NOTIFY: 'M01 通知',
  CALL_TRANSFER: '呼叫转接',
  NURSING_TASK: '护理任务',
  WARD_BROADCAST: '病区播报',
};

/** 联动执行结果三值中文词表（SUCCESS 成功/FAILED 失败/PENDING 待执行——执行日志徽标） */
export const LINKAGE_RESULT_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  PENDING: '待执行',
};

/** 消费错误环节三值中文词表（PARSE 解析/VALIDATE 校验/PERSIST 落库——错误列表环节列） */
export const CONSUME_ERROR_STAGE_LABELS: Record<string, string> = {
  PARSE: '解析',
  VALIDATE: '校验',
  PERSIST: '落库',
};

/** 消费错误状态三值中文词表（PENDING 待处置/REPLAYED 已重放/ABANDONED 已放弃） */
export const CONSUME_ERROR_STATUS_LABELS: Record<string, string> = {
  PENDING: '待处置',
  REPLAYED: '已重放',
  ABANDONED: '已放弃',
};

/** 指标类别四值中文词表（指标字典列表与新增下拉共用） */
export const METRIC_CATEGORY_LABELS: Record<string, string> = {
  VITAL_SIGN: '体征',
  WAVEFORM: '波形',
  ALARM: '报警',
  DEVICE_STATUS: '设备状态',
};

/** 命令安全等级两值中文词表（安全级=查询/展示类；治疗级=给药/通气参数类，默认禁用） */
export const SAFETY_LEVEL_LABELS: Record<string, string> = {
  SAFETY: '安全级',
  TREATMENT: '治疗级',
};

/** 病区选项（冻结 REST 面无病区清单端点，P1 以演示病区种子 W01 前端常量承载，
 * 与 inpatient/nursing 域同源；P2 对齐 M01 组织机构病区后换接口源） */
export const IOT_WARD_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'W01', label: 'W01 演示病区' },
];

/** 产品资源组：分页 / 上架 / 物模型同步 / 术语映射整组替换 / 命令安全等级整组替换。 */
export const products = {
  /** 产品分页（IoTDA 产品本地镜像；syncStatus 空=全部状态）。 */
  list: async (params: ProductQueryRequest): Promise<ProductPage> => {
    const resp = await http.get<ProductPage>('/v1/iot/products', { params });
    return resp.data;
  },
  /** 产品上架（建 IoTDA 镜像；modelDefinitionJson 物模型 JSON 快照可空后续同步）。 */
  create: async (payload: CreateProductRequest): Promise<ProductVO> => {
    const resp = await http.post<ProductVO>('/v1/iot/products', payload);
    return resp.data;
  },
  /** 物模型同步（触发 IoTDA 开放 API 拉取物模型定义对齐镜像，返回同步后产品态）。 */
  syncModel: async (productId: string): Promise<ProductVO> => {
    const resp = await http.post<ProductVO>(`/v1/iot/products/${productId}/model-sync`);
    return resp.data;
  },
  /** 术语映射整组替换（物模型属性→MDC 指标编码归一；返回替换后映射全集）。 */
  updateMappings: async (
    productId: string,
    payload: { mappings: MappingItem[] },
  ): Promise<MetricMappingVO[]> => {
    const resp = await http.put<MetricMappingVO[]>(
      `/v1/iot/products/${productId}/metric-mappings`,
      payload,
    );
    return resp.data;
  },
  /** 命令安全等级整组替换（FU-M14-09 白名单数据源；返回替换后命令全集）。 */
  updateCommands: async (
    productId: string,
    payload: { commands: CommandItem[] },
  ): Promise<CommandVO[]> => {
    const resp = await http.put<CommandVO[]>(`/v1/iot/products/${productId}/commands`, payload);
    return resp.data;
  },
};

/** 指标字典资源组：分页（MDC 编码跨品牌归一口径）/ 新增。 */
export const metrics = {
  /** 指标字典全量（category 可空=全类别；量小全量直出，无分页）。 */
  list: async (params: { category?: string }): Promise<MetricDictVO[]> => {
    const resp = await http.get<MetricDictVO[]>('/v1/iot/metrics', { params });
    return resp.data;
  },
  /** 指标新增（MDC 编码唯一性由后端把守）。 */
  create: async (payload: CreateMetricRequest): Promise<MetricDictVO> => {
    const resp = await http.post<MetricDictVO>('/v1/iot/metrics', payload);
    return resp.data;
  },
};

/** 设备资源组：分页 / 注册 / 详情 / 影子 / 停用 / 凭证重置。 */
export const devices = {
  /** 设备分页（status 空=全部五态；productId/wardId 可空不过滤）。 */
  list: async (params: DeviceQueryRequest): Promise<DevicePage> => {
    const resp = await http.get<DevicePage>('/v1/iot/devices', { params });
    return resp.data;
  },
  /** 设备注册（经 IoTDA 设备管理 API 创建并签发一机一密凭证，凭证密文托管）。 */
  register: async (payload: DeviceRegisterRequest): Promise<DeviceVO> => {
    const resp = await http.post<DeviceVO>('/v1/iot/devices', payload);
    return resp.data;
  },
  /** 设备详情（注册后与操作前回显共用）。 */
  detail: async (deviceId: string): Promise<DeviceVO> => {
    const resp = await http.get<DeviceVO>(`/v1/iot/devices/${deviceId}`);
    return resp.data;
  },
  /** 设备影子（IoTDA desired/reported 两区查询——最后上报状态面）。 */
  shadow: async (deviceId: string): Promise<DeviceShadowVO> => {
    const resp = await http.get<DeviceShadowVO>(`/v1/iot/devices/${deviceId}/shadow`);
    return resp.data;
  },
  /** 设备停用（ONLINE/OFFLINE/ABNORMAL→DISABLED；停用设备遥测拒收由后端把守）。 */
  disable: async (deviceId: string): Promise<void> => {
    await http.post(`/v1/iot/devices/${deviceId}/disable`);
  },
  /** 凭证重置（一机一密重签；secret 明文仅本次响应返回一次，前端弹窗展示不落库）。 */
  resetCredential: async (deviceId: string): Promise<DeviceCredentialResetVO> => {
    const resp = await http.post<DeviceCredentialResetVO>(
      `/v1/iot/devices/${deviceId}/credential-reset`,
    );
    return resp.data;
  },
};

/** 绑定资源组：分页 / 绑定 / 解绑（绑定历史只增，解绑原因强制）。 */
export const bindings = {
  /** 绑定分页（status 空=全部三态；deviceId/wardId 可空不过滤）。 */
  list: async (params: BindingQueryRequest): Promise<BindingPage> => {
    const resp = await http.get<BindingPage>('/v1/iot/bindings', { params });
    return resp.data;
  },
  /** 绑定（快照五元组设备×患者×就诊×床位×病区；visit_id 14 位 I 前缀格式校验前端显式承载）。 */
  bind: async (payload: BindDeviceRequest): Promise<BindingVO> => {
    const resp = await http.post<BindingVO>('/v1/iot/bindings', payload);
    return resp.data;
  },
  /** 解绑（原因强制：转床/消毒/维修/出院/调拨口径；解绑受理置 UNBINDING）。 */
  unbind: async (deviceId: string, payload: UnbindDeviceRequest): Promise<void> => {
    await http.post(`/v1/iot/bindings/${deviceId}/unbind`, payload);
  },
};

/** 告警规则资源组：列表 / 新建 / 修改 / 删除 / 模拟回放。 */
export const alarmRules = {
  /** 规则全量列表（量小全量直出，无分页；前端按返回序直出）。 */
  list: async (): Promise<AlarmRuleVO[]> => {
    const resp = await http.get<AlarmRuleVO[]>('/v1/iot/alarm-rules');
    return resp.data;
  },
  /** 规则新建（THRESHOLD 类 durationSecs/recoveryBand 必填由前端显式校验+后端兜底）。 */
  create: async (payload: SaveAlarmRuleRequest): Promise<AlarmRuleVO> => {
    const resp = await http.post<AlarmRuleVO>('/v1/iot/alarm-rules', payload);
    return resp.data;
  },
  /** 规则修改（整单替换语义，SaveAlarmRuleRequest 同新建）。 */
  update: async (id: string, payload: SaveAlarmRuleRequest): Promise<AlarmRuleVO> => {
    const resp = await http.put<AlarmRuleVO>(`/v1/iot/alarm-rules/${id}`, payload);
    return resp.data;
  },
  /** 规则删除（204 无返回体）。 */
  remove: async (id: string): Promise<void> => {
    await http.delete(`/v1/iot/alarm-rules/${id}`);
  },
  /** 模拟回放（历史遥测按时间窗回放验证规则效果，返回扫描行数与命中触发清单）。 */
  simulate: async (id: string, payload: SimulateAlarmRequest): Promise<SimulateResultVO> => {
    const resp = await http.post<SimulateResultVO>(`/v1/iot/alarm-rules/${id}/simulate`, payload);
    return resp.data;
  },
};

/** 告警资源组：分页 / 确认 / 关闭（闭环接口供工作台与 PDA 共用）。 */
export const alarms = {
  /** 告警分页（status 空=全部三态；等级/病区可空不过滤）。 */
  list: async (params: AlarmQueryRequest): Promise<AlarmPage> => {
    const resp = await http.get<AlarmPage>('/v1/iot/alarms', { params });
    return resp.data;
  },
  /** 告警确认（ACTIVE→ACKNOWLEDGED；确认人取会话，由后端承载）。 */
  acknowledge: async (alarmNo: string): Promise<AlarmVO> => {
    const resp = await http.post<AlarmVO>(`/v1/iot/alarms/${alarmNo}/acknowledge`);
    return resp.data;
  },
  /** 告警关闭（ACTIVE/ACKNOWLEDGED→CLOSED——后端 AlarmServiceImpl casClose CAS
   * status IN ('ACTIVE','ACKNOWLEDGED') 实况口径，终态已关闭拒绝；原因强制由后端把守，
   * 前端表单显式校验）。 */
  close: async (alarmNo: string, payload: CloseAlarmRequest): Promise<AlarmVO> => {
    const resp = await http.post<AlarmVO>(`/v1/iot/alarms/${alarmNo}/close`, payload);
    return resp.data;
  },
};

/** 命令资源组：挑战确认 / 下发 / 日志分页（challenge 两步安全门：第一步 confirm-challenge
 * 签发 challengeId，第二步凭 challengeId 下发；治疗级命令白名单校验由后端在两步把守）。 */
export const commands = {
  /** 第一步·挑战确认（设备/命令/参数预检，返回一次性 challengeId 与有效期；FU-M14-09 安全门）。 */
  confirmChallenge: async (payload: ConfirmChallengeRequest): Promise<ConfirmChallengeVO> => {
    const resp = await http.post<ConfirmChallengeVO>('/v1/iot/commands/confirm-challenge', payload);
    return resp.data;
  },
  /** 第二步·下发（携 challengeId 幂等锚下发；返回命令日志行，状态从 ISSUED 起流转）。 */
  issue: async (payload: IssueCommandRequest): Promise<CommandLogVO> => {
    const resp = await http.post<CommandLogVO>('/v1/iot/commands', payload);
    return resp.data;
  },
  /** 命令日志分页（status 空=全部五态；deviceId 可空不过滤）。 */
  page: async (params: CommandQueryRequest): Promise<CommandLogPage> => {
    const resp = await http.get<CommandLogPage>('/v1/iot/commands', { params });
    return resp.data;
  },
};

/** 联动规则资源组：列表 / 新建 / 修改 / 删除（触发条件与动作配置为 JSON 对象，前端显式
 * JSON 校验禁裸 parse）。 */
export const linkageRules = {
  /** 规则全量列表（量小全量直出，无分页）。 */
  list: async (): Promise<LinkageRuleVO[]> => {
    const resp = await http.get<LinkageRuleVO[]>('/v1/iot/linkage-rules');
    return resp.data;
  },
  /** 规则新建（enabled 缺省由后端承载）。 */
  create: async (payload: SaveLinkageRuleRequest): Promise<LinkageRuleVO> => {
    const resp = await http.post<LinkageRuleVO>('/v1/iot/linkage-rules', payload);
    return resp.data;
  },
  /** 规则修改（整单替换语义，SaveLinkageRuleRequest 同新建）。 */
  update: async (id: string, payload: SaveLinkageRuleRequest): Promise<LinkageRuleVO> => {
    const resp = await http.put<LinkageRuleVO>(`/v1/iot/linkage-rules/${id}`, payload);
    return resp.data;
  },
  /** 规则删除（204 无返回体）。 */
  remove: async (id: string): Promise<void> => {
    await http.delete(`/v1/iot/linkage-rules/${id}`);
  },
};

/** 联动执行日志资源组：分页 / 重试（FAILED 行可重试，重试计数由后端累加）。 */
export const linkageLogs = {
  /** 执行日志分页（ruleId/triggerSource/actionResult 可空不过滤）。 */
  page: async (params: LinkageLogQueryRequest): Promise<LinkageLogPage> => {
    const resp = await http.get<LinkageLogPage>('/v1/iot/linkage-logs', { params });
    return resp.data;
  },
  /** 失败重试（按 linkageNo 重投动作；返回重试后日志行）。 */
  retry: async (linkageNo: string): Promise<LinkageLogVO> => {
    const resp = await http.post<LinkageLogVO>(`/v1/iot/linkage-logs/${linkageNo}/retry`);
    return resp.data;
  },
};

/** 数据质量资源组：质量统计分页 / 设备利用率分页（TopN 由前端按 usageRate 降序截取）。 */
export const quality = {
  /** 质量统计分页（deviceId/statDate 可空不过滤；missingRate 缺测率、anomalyCount 异常数）。 */
  stats: async (params: QualityStatQueryRequest): Promise<QualityStatPage> => {
    const resp = await http.get<QualityStatPage>('/v1/iot/quality/stats', { params });
    return resp.data;
  },
  /** 设备利用率分页（usageRate 利用率；TopN 形态由前端排序承载）。 */
  deviceUsage: async (params: QualityStatQueryRequest): Promise<QualityStatPage> => {
    const resp = await http.get<QualityStatPage>('/v1/iot/quality/device-usage', { params });
    return resp.data;
  },
};

/** 消费监控资源组：消费组积压快照（消费速率/到达速率/积压估计/最老消息年龄）。 */
export const monitor = {
  /** 消费组积压全量（量小全量直出；backlogEstimate 积压水位核心字段）。 */
  consumerLag: async (): Promise<ConsumerStatVO[]> => {
    const resp = await http.get<ConsumerStatVO[]>('/v1/iot/monitor/consumer-lag');
    return resp.data;
  },
};

/** 消费错误资源组：分页 / 重放 / 放弃（死信治理面：重放重投原消息，放弃须留原因）。 */
export const consumeErrors = {
  /** 错误分页（status 空=全部三态；queueName 可空不过滤）。 */
  page: async (params: ConsumeErrorQueryRequest): Promise<ConsumeErrorPage> => {
    const resp = await http.get<ConsumeErrorPage>('/v1/iot/consume-errors', { params });
    return resp.data;
  },
  /** 重放（原消息重投消费链路；返回重放后错误行——replayCount 累加）。 */
  replay: async (errorId: string): Promise<ConsumeErrorVO> => {
    const resp = await http.post<ConsumeErrorVO>(`/v1/iot/consume-errors/${errorId}/replay`);
    return resp.data;
  },
  /** 放弃（原因强制：前端显式校验+后端兜底；放弃后不再重投，留痕由后端承载）。 */
  abandon: async (
    errorId: string,
    payload: AbandonConsumeErrorRequest,
  ): Promise<ConsumeErrorVO> => {
    const resp = await http.post<ConsumeErrorVO>(
      `/v1/iot/consume-errors/${errorId}/abandon`,
      payload,
    );
    return resp.data;
  },
};

/** 遥测查询资源组：曲线 series（scope=device/patient/ward 三维度路由，冷链温度曲线轻量
 * 渲染消费——不引 echarts）。 */
export const telemetry = {
  /** 遥测曲线查询（返回聚合点列：time/min/max/avg/first/last/sampleCount）。 */
  series: async (params: TelemetrySeriesRequest): Promise<TelemetryPoint[]> => {
    const resp = await http.get<TelemetryPoint[]>('/v1/iot/telemetry/series', { params });
    return resp.data;
  },
};
