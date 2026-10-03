// 护理域 API 层单测（PR-3 扩展面）：executions/infusions/adverse-events/tasks.claim/
// generate-routine 资源组的路径与载荷透传断言（mock http 模块，不打真实网络）；P1 存量
// 资源组（ward-patients/体征/任务完成取消等）的出网行为已由消费方 spec 覆盖，本文件
// 不重复。拦截器链归 http.spec.ts。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { http } from './http';
import { adverseEvents, executions, infusions, tasks } from './nursing';

// http 单例以纯函数替身承载（api 层职责=路径与载荷组装，无其他协作者）
vi.mock('./http', () => ({
  http: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

// 替身以数据属性形态取用（AxiosInstance 上的 get/post 为接口方法，直接引用触 unbound-method 误报）
const httpMock = http as unknown as { get: Mock; post: Mock };

/** 构造 axios 响应壳（api 层只消费 data 字段，其余字段以断言占位） */
function resp(data: unknown) {
  return { data, status: 200, statusText: 'OK', headers: {}, config: {} };
}

describe('护理域 API 层（PR-3 扩展资源组）', () => {
  beforeEach(() => {
    httpMock.get.mockReset();
    httpMock.post.mockReset();
  });

  it('执行单分组清单 GET 契约路径并整体透传查询参数（page 为 0 基）', async () => {
    httpMock.get.mockResolvedValue(resp({ content: [], page: '0', size: '200', total: '0' }));

    const page = await executions.list({ wardId: 'W01', date: '2026-10-01', page: 0, size: 200 });

    expect(httpMock.get).toHaveBeenCalledWith('/v1/nursing/executions', {
      params: { wardId: 'W01', date: '2026-10-01', page: 0, size: 200 },
    });
    expect(page.total).toBe('0');
  });

  it('执行单闭环追溯 GET 路径插值执行单号（雪花单号 string 原样入路径）', async () => {
    httpMock.get.mockResolvedValue(resp({ executionNo: 'EX2026100100001' }));

    const trace = await executions.trace('EX2026100100001');

    expect(httpMock.get).toHaveBeenCalledWith('/v1/nursing/executions/EX2026100100001/trace');
    expect(trace.executionNo).toBe('EX2026100100001');
  });

  it('执行单五动作 POST 契约路径并透传各自请求体（sign-receive/check/start/finish/cancel）', async () => {
    httpMock.post.mockResolvedValue(resp({ executionNo: 'EX2026100100001' }));

    await executions.signReceive('EX2026100100001', { receivedNote: '床旁补签收' });
    expect(httpMock.post).toHaveBeenCalledWith(
      '/v1/nursing/executions/EX2026100100001/sign-receive',
      {
        receivedNote: '床旁补签收',
      },
    );

    await executions.check('EX2026100100001', {
      code: 'I20260923000000001',
      codeType: 'WRISTBAND',
    });
    expect(httpMock.post).toHaveBeenCalledWith('/v1/nursing/executions/EX2026100100001/check', {
      code: 'I20260923000000001',
      codeType: 'WRISTBAND',
    });

    await executions.start('EX2026100100001', { executorId: 'u1' });
    expect(httpMock.post).toHaveBeenCalledWith('/v1/nursing/executions/EX2026100100001/start', {
      executorId: 'u1',
    });

    await executions.finish('EX2026100100001', { executorId: 'u1' });
    expect(httpMock.post).toHaveBeenCalledWith('/v1/nursing/executions/EX2026100100001/finish', {
      executorId: 'u1',
    });

    await executions.cancel('EX2026100100001', { reason: '医嘱作废' });
    expect(httpMock.post).toHaveBeenCalledWith('/v1/nursing/executions/EX2026100100001/cancel', {
      reason: '医嘱作废',
    });
  });

  it('病区在途输注清单 GET 契约路径携 wardId 查询参数', async () => {
    httpMock.get.mockResolvedValue(resp([{ executionNo: 'EX2026100100001' }]));

    const rows = await infusions.active('W01');

    expect(httpMock.get).toHaveBeenCalledWith('/v1/nursing/infusions/active', {
      params: { wardId: 'W01' },
    });
    expect(rows).toHaveLength(1);
  });

  it('不良事件分页 GET 契约路径并整体透传筛选参数', async () => {
    httpMock.get.mockResolvedValue(resp({ content: [], page: '0', size: '20', total: '0' }));

    const page = await adverseEvents.list({
      category: 'FALL',
      status: 'REPORTED',
      page: 0,
      size: 20,
    });

    expect(httpMock.get).toHaveBeenCalledWith('/v1/nursing/adverse-events', {
      params: { category: 'FALL', status: 'REPORTED', page: 0, size: 20 },
    });
    expect(page.content).toEqual([]);
  });

  it('不良事件四动作 POST 契约路径并透传请求体（report/handle/close/return）', async () => {
    httpMock.post.mockResolvedValue(resp({ eventNo: 'AE2026100100001' }));

    await adverseEvents.report({
      category: 'FALL',
      severityClass: 'II',
      severityGrade: 'B',
      wardId: 'W01',
      occurredAt: '2026-10-01T08:00:00',
      eventSummary: '病房走廊跌倒',
      isAnonymous: false,
    });
    expect(httpMock.post).toHaveBeenCalledWith('/v1/nursing/adverse-events', {
      category: 'FALL',
      severityClass: 'II',
      severityGrade: 'B',
      wardId: 'W01',
      occurredAt: '2026-10-01T08:00:00',
      eventSummary: '病房走廊跌倒',
      isAnonymous: false,
    });

    await adverseEvents.handle('AE2026100100001', { handlerId: 'u1', handlingNote: '已现场处置' });
    expect(httpMock.post).toHaveBeenCalledWith(
      '/v1/nursing/adverse-events/AE2026100100001/handle',
      { handlerId: 'u1', handlingNote: '已现场处置' },
    );

    await adverseEvents.close('AE2026100100001', { closedBy: 'u1', rcaNote: ' RCA 结论' });
    expect(httpMock.post).toHaveBeenCalledWith('/v1/nursing/adverse-events/AE2026100100001/close', {
      closedBy: 'u1',
      rcaNote: ' RCA 结论',
    });

    await adverseEvents.return('AE2026100100001', { reason: '信息不全退回补充', returnerId: 'u1' });
    expect(httpMock.post).toHaveBeenCalledWith(
      '/v1/nursing/adverse-events/AE2026100100001/return',
      {
        reason: '信息不全退回补充',
        returnerId: 'u1',
      },
    );
  });

  it('任务认领与常规模板生成 POST 契约路径并透传请求体', async () => {
    httpMock.post.mockResolvedValue(resp({ taskNo: 'TK2026100100001' }));

    await tasks.claim('TK2026100100001', { assigneeId: 'u1' });
    expect(httpMock.post).toHaveBeenCalledWith('/v1/nursing/tasks/TK2026100100001/claim', {
      assigneeId: 'u1',
    });

    httpMock.post.mockResolvedValue(resp({ createdTasks: 6 }));
    const generated = await tasks.generateRoutine({ wardId: 'W01', date: '2026-10-01' });
    expect(httpMock.post).toHaveBeenCalledWith('/v1/nursing/tasks/generate-routine', {
      wardId: 'W01',
      date: '2026-10-01',
    });
    expect(generated.createdTasks).toBe(6);
  });
});
