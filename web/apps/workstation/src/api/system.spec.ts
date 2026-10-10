// 系统管理域 API 层单测（orgs/dicts 消费面新增段）：mock http 模块断言出网路径与查询参数、
// 返回直通与 reject 冒泡（不打真实网络）；既有角色管理四函数的编排形态由视图层消费验证，
// 拦截器链（弹错/traceId/401）归 http.spec.ts 覆盖，本文件不重复。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { http } from './http';
import { listDictItems, listOrgs } from './system';

// http 单例以纯函数替身承载（api 层职责=路径与参数组装，无其他协作者；system.ts 另有 put 面，
// 本文件仅触达 get，替身按需提供）
vi.mock('./http', () => ({
  http: { get: vi.fn(), put: vi.fn() },
}));

// 替身以数据属性形态取用（AxiosInstance 上的 get 为接口方法，直接引用触 unbound-method 误报）
const httpMock = http as unknown as { get: Mock; put: Mock };

/** 构造 axios 响应壳（api 层只消费 data 字段，其余字段以断言占位） */
function resp(data: unknown) {
  return { data, status: 200, statusText: 'OK', headers: {}, config: {} };
}

describe('系统管理域 orgs/dicts 消费面 API', () => {
  beforeEach(() => {
    httpMock.get.mockReset();
    httpMock.put.mockReset();
  });

  it('listOrgs WARD：GET /v1/system/orgs 携 type 查询参数并直通返回清单', async () => {
    const wards = [
      { id: '1123000000000000001', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD' },
    ];
    httpMock.get.mockResolvedValue(resp(wards));

    const result = await listOrgs({ type: 'WARD' });

    expect(httpMock.get).toHaveBeenCalledWith('/v1/system/orgs', { params: { type: 'WARD' } });
    expect(result).toBe(wards);
  });

  it('listOrgs DEPT：科室类型同端点同参形态（病区/科室共用一读端点）', async () => {
    const depts = [
      { id: '1123000000000000003', orgCode: 'DEPT-INT', orgName: '内科', orgType: 'DEPT' },
    ];
    httpMock.get.mockResolvedValue(resp(depts));

    const result = await listOrgs({ type: 'DEPT' });

    expect(httpMock.get).toHaveBeenCalledWith('/v1/system/orgs', { params: { type: 'DEPT' } });
    expect(result).toEqual(depts);
  });

  it('listDictItems：GET /v1/system/dicts/{typeCode} 路径插值并直取版本条目清单', async () => {
    const items = [{ itemCode: 'M', itemName: '男', sort: 1 }];
    httpMock.get.mockResolvedValue(resp({ typeCode: 'gender', version: 1, items }));

    const result = await listDictItems('gender');

    expect(httpMock.get).toHaveBeenCalledWith('/v1/system/dicts/gender');
    expect(result).toBe(items);
  });

  it('listDictItems：条目缺席时返回空数组兜底（版本无条目不向视图层漏 null）', async () => {
    httpMock.get.mockResolvedValue(resp({ typeCode: 'gender', version: 1, items: undefined }));

    const result = await listDictItems('gender');

    expect(httpMock.get).toHaveBeenCalledWith('/v1/system/dicts/gender');
    expect(result).toEqual([]);
  });

  it('orgs 出网失败：reject 原样冒泡（api 层不吞错，统一出口归拦截器）', async () => {
    httpMock.get.mockRejectedValue(new Error('网络中断'));

    await expect(listOrgs({ type: 'WARD' })).rejects.toThrow('网络中断');
  });
});
