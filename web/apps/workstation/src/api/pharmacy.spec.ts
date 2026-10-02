// 药事域 API 层单测（PR-3 Task 16 扩展面）：dispensePlans 资源组八端点的路径与载荷透传
// 断言（mock http 模块，不打真实网络）；既有资源组（字典/处方/调剂三段/退药/审方）的
// 出网行为已由消费方 spec 覆盖，本文件不重复。拦截器链归 http.spec.ts。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { http } from './http';
import { dispensePlans } from './pharmacy';

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

describe('药事域 API 层（dispensePlans 资源组）', () => {
  beforeEach(() => {
    httpMock.get.mockReset();
    httpMock.post.mockReset();
  });

  it('摆药计划分页 GET 契约路径并整体透传查询参数（page 为 0 基）', async () => {
    httpMock.get.mockResolvedValue(resp({ content: [], page: '0', size: '200', total: '0' }));

    const page = await dispensePlans.list({
      wardId: 'W01',
      m04OrderNo: 'MO2026100200001',
      page: 0,
      size: 200,
    });

    expect(httpMock.get).toHaveBeenCalledWith('/v1/pharmacy/dispense-plans', {
      params: { wardId: 'W01', m04OrderNo: 'MO2026100200001', page: 0, size: 200 },
    });
    expect(page.total).toBe('0');
  });

  it('摆药计划生成 POST 契约路径并透传医嘱号与目标病区', async () => {
    httpMock.post.mockResolvedValue(resp([{ planNo: 'DP2026100200001' }]));

    const plans = await dispensePlans.generate({ m04OrderNo: 'MO2026100200001', wardId: 'W01' });

    expect(httpMock.post).toHaveBeenCalledWith('/v1/pharmacy/dispense-plans/generate', {
      m04OrderNo: 'MO2026100200001',
      wardId: 'W01',
    });
    expect(plans).toHaveLength(1);
  });

  it('摆药流前三步 POST 契约路径无请求体（pick/verify/issue）', async () => {
    httpMock.post.mockResolvedValue(resp(undefined));

    await dispensePlans.pick('DP2026100200001');
    expect(httpMock.post).toHaveBeenCalledWith('/v1/pharmacy/dispense-plans/DP2026100200001/pick');

    await dispensePlans.verify('DP2026100200001');
    expect(httpMock.post).toHaveBeenCalledWith(
      '/v1/pharmacy/dispense-plans/DP2026100200001/verify',
    );

    await dispensePlans.issue('DP2026100200001');
    expect(httpMock.post).toHaveBeenCalledWith('/v1/pharmacy/dispense-plans/DP2026100200001/issue');
  });

  it('配送交接 POST 契约路径并透传配送人（缺省入参形态同断言）', async () => {
    httpMock.post.mockResolvedValue(resp(undefined));

    await dispensePlans.deliver('DP2026100200001', { carrier: '工勤 T01' });
    expect(httpMock.post).toHaveBeenCalledWith(
      '/v1/pharmacy/dispense-plans/DP2026100200001/deliver',
      {
        carrier: '工勤 T01',
      },
    );

    // carrier 可缺省（后端 body 可缺省合法）：无载荷形态不带请求体出网
    await dispensePlans.deliver('DP2026100200001');
    expect(httpMock.post).toHaveBeenLastCalledWith(
      '/v1/pharmacy/dispense-plans/DP2026100200001/deliver',
    );
  });

  it('病区签收 POST 契约路径并透传签收人工号（string 契约承载 Long）', async () => {
    httpMock.post.mockResolvedValue(resp(undefined));

    await dispensePlans.receive('DP2026100200001', { receivedBy: '1001' });

    expect(httpMock.post).toHaveBeenCalledWith(
      '/v1/pharmacy/dispense-plans/DP2026100200001/receive',
      {
        receivedBy: '1001',
      },
    );
  });

  it('PIVAS 贴签数据面 GET 路径插值计划号', async () => {
    httpMock.get.mockResolvedValue(
      resp({ planNo: 'DP2026100200001', pivasBatchNo: 'DPB20261002001' }),
    );

    const label = await dispensePlans.label('DP2026100200001');

    expect(httpMock.get).toHaveBeenCalledWith('/v1/pharmacy/dispense-plans/DP2026100200001/label');
    expect(label.pivasBatchNo).toBe('DPB20261002001');
  });
});
