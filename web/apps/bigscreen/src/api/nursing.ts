/**
 * 护理/病房域 API（bigscreen 护士站大屏消费面，一域一文件，web A.3-5 模块化）：护理大屏
 * 四段快照（REST 兜底，WS 主通道增量）+ 病区输液看板快照（REST 唯一来源——infusion-board
 * 无 WS 推送主题）。路径前缀 /v1/nursing、/v1/ward（baseURL 已含 /api）；雪花 id 与 long
 * 后端经 Jackson 全局以字符串输出（backend A.3-8），前端类型一律 string 承载（web A.3-6）；
 * 生成物为唯一类型来源（Task 11 冻结契约）。匿名只读面（与 iot.ts 同口径）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
/** 护理大屏四段快照（床位墙/任务逾期/出入院动态/危急值占位 + 生成时点） */
export type NurseBoardVO = components['schemas']['NurseBoardVO'];
/** 床位总览墙行（床号/就诊号/护理级别/入区时点/责任护士/风险标记） */
export type BedRow = components['schemas']['BedRow'];
/** 任务逾期清单行（任务号/类型/计划时间/升级次数） */
export type OverdueTaskRow = components['schemas']['OverdueTaskRow'];
/** 出入院动态时间线行（就诊号/床号/时点/类型 ADMIT|DISCHARGE） */
export type AdmissionRow = components['schemas']['AdmissionRow'];
/** 病区输液看板快照（设备行清单） */
export type InfusionBoardVO = components['schemas']['InfusionBoardVO'];
/** 输液看板设备行（余量/滴速最新值与告警档位——档位判定归后端 mapAlertLevel） */
export type InfusionBoardDeviceRow = components['schemas']['InfusionBoardDeviceVO'];

/** 护理大屏资源组：四段快照（首屏与 WS 断连 10s 轮询兜底；服务端 Redis TTL 5s read-through）。 */
export const nursing = {
  /** 病区大屏四段快照（床位墙 beds/逾期 overdueTasks/出入院 admissions/危急值占位）。 */
  board: async (wardId: string): Promise<NurseBoardVO> => {
    const resp = await http.get<NurseBoardVO>(`/v1/nursing/board/${wardId}`);
    return resp.data;
  },
};

/** 病房输液看板资源组：病区快照（输液动态条唯一数据源——遥测帧仅作刷新信号）。 */
export const ward = {
  /** 病区输液看板快照（余量/滴速/档位由后端判定承载；无遥测设备余量为 null）。 */
  infusionBoard: async (wardId: string): Promise<InfusionBoardVO> => {
    const resp = await http.get<InfusionBoardVO>(`/v1/ward/infusion-board/${wardId}`);
    return resp.data;
  },
};
