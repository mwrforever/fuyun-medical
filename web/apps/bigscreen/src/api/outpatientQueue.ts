/**
 * bigscreen 门诊队列 REST API（web A.3-5 模块化）：队列快照首屏拉取（实时增量走
 * /ws/outpatient STOMP 订阅，双通道之一 Spec :153）。大屏为受控演示面：本模块与 useQueueStomp
 * 是仅有的两个出网口，未配置大屏令牌时页面层整链路禁用（零 REST 零订阅）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type QueueTicketVO = components['schemas']['QueueTicketVO'];

/**
 * 队列 REST 快照（脱敏出网：patientName 已是 patient 侧掩码展示名）。队列标识=诊区编码
 * （后端 snapshot 的 queueId 即 dept_code，路径参数同名承载）。
 *
 * @param queueId 队列标识（=deptCode）；来源：路由 query 或页面诊区输入
 * @return 票据出参列表（按优先级降序、同分按建行时间升序）；空队列为空列表
 * @throws ScreenApiError 非 2xx 归一化错误（横幅呈现归页面）
 */
export async function getQueueSnapshot(queueId: string): Promise<QueueTicketVO[]> {
  const resp = await http.get<QueueTicketVO[]>(`/v1/outpatient/queues/${queueId}/tickets`);
  return resp.data;
}
