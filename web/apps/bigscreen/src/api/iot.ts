/**
 * 物联网域 API（bigscreen 大屏消费面，一域一文件）：运营大屏 dashboard（全院摘要/病区床位
 * 设备状态墙）+ 遥测曲线（series）+ 告警分页（WS 断连期中列告警的 REST 兜底）。路径前缀
 * /v1/iot/**（baseURL 已含 /api）；雪花 id 与 long 后端经 Jackson 全局以字符串输出
 * （backend A.3-8），前端类型一律 string 承载（web A.3-6）。REST 面为后端 Task 10/11 冻结
 * 契约（生成物唯一来源）；函数按资源分组导出（spec mock 面）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
/** 全院运营摘要六项（设备总数/在线/离线/告警活跃/风暴态/积压水位/质量分） */
export type DashboardSummaryVO = components['schemas']['DashboardSummaryVO'];
/** 病区床位设备状态墙（绑定五元组 + 设备状态 + 最新值清单） */
export type WardDeviceWallVO = components['schemas']['WardDeviceWallVO'];
/** 状态墙单条目（一绑定一行：床位/设备/患者/就诊/状态/最后在线/最新值） */
export type BedDeviceItem = components['schemas']['BedDeviceItem'];
/** 遥测曲线查询入参（scope=device/patient/ward 三维度路由；granularity raw|1min|1h） */
export type TelemetrySeriesRequest = components['schemas']['TelemetrySeriesRequest'];
/** 遥测聚合点（time/min/max/avg/first/last/sampleCount——曲线渲染消费） */
export type TelemetryPoint = components['schemas']['TelemetryPoint'];
/** 告警出参（REST 兜底列表行；等级/状态词表见 workstation 同域，大屏仅展示） */
export type AlarmVO = components['schemas']['AlarmVO'];
/** 告警分页查询入参（wardId/status 过滤——大屏兜底拉活跃告警） */
export type AlarmQueryRequest = components['schemas']['AlarmQueryRequest'];

/** 告警分页出参（common PageResult 单泛型生成物：content/page/size/total） */
export type AlarmPage = components['schemas']['PageResultAlarmVO'];

/** 运营大屏资源组：全院摘要（Redis 快照 TTL 5s read-through）/ 病区床位设备状态墙。 */
export const dashboard = {
  /** 全院运营摘要（六项聚合：设备在线/离线/告警活跃/风暴态/积压水位/质量分）。 */
  summary: async (): Promise<DashboardSummaryVO> => {
    const resp = await http.get<DashboardSummaryVO>('/v1/iot/dashboard/summary');
    return resp.data;
  },
  /** 病区床位设备状态墙（绑定五元组+设备状态+最新值三面拼装；绑定 id 升序）。 */
  wardWall: async (wardId: string): Promise<WardDeviceWallVO> => {
    const resp = await http.get<WardDeviceWallVO>(`/v1/iot/dashboard/wards/${wardId}`);
    return resp.data;
  },
};

/** 遥测查询资源组：曲线 series（大屏趋势图固定 1min 聚合桶，scope=ward）。 */
export const telemetry = {
  /** 遥测曲线查询（返回聚合点列：time/min/max/avg/first/last/sampleCount）。 */
  series: async (params: TelemetrySeriesRequest): Promise<TelemetryPoint[]> => {
    const resp = await http.get<TelemetryPoint[]>('/v1/iot/telemetry/series', { params });
    return resp.data;
  },
};

/** 告警资源组（大屏只读消费）：分页（WS 断连期活跃告警列表的 REST 兜底数据源）。 */
export const alarms = {
  /** 告警分页（大屏固定拉 status=ACTIVE + wardId 过滤的首页）。 */
  list: async (params: AlarmQueryRequest): Promise<AlarmPage> => {
    const resp = await http.get<AlarmPage>('/v1/iot/alarms', { params });
    return resp.data;
  },
};
