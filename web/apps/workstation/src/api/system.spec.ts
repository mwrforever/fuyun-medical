// 系统管理域 API 层单测：mock http 模块断言出网路径与查询参数、返回过滤与 reject 冒泡
// （不打真实网络）。listOrgs WARD 档按纯数字契约过滤（册 0 P-GBL-02 止血：V1123 种子 W01
// 形态脏行不回给任何下拉消费方，对齐 useIotStomp WARD_ID_PATTERN 同源契约）；DEPT 档仅滤
// 空 orgCode（DEPT-INT 系带连字符为合法科室码形态，禁过度过滤——滤光诊区即瘫痪门诊）。
// 既有角色管理四函数的编排形态由视图层消费验证，拦截器链（弹错/traceId/401）归
// http.spec.ts 覆盖，本文件不重复。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import * as logger from '../utils/logger';
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

  it('listOrgs WARD：GET /v1/system/orgs 携 type 查询参数，非纯数字病区码过滤（W01 形态不回给任何下拉消费方）', async () => {
    const wards = [
      { id: '1123000000000000001', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD' },
      { id: '1123000000000000002', orgCode: '1001', orgName: '一病区', orgType: 'WARD' },
    ];
    httpMock.get.mockResolvedValue(resp(wards));

    const result = await listOrgs({ type: 'WARD' });

    // 出网路径与查询参数零变动（过滤是封装层行为，不改查询语义）
    expect(httpMock.get).toHaveBeenCalledWith('/v1/system/orgs', { params: { type: 'WARD' } });
    // 仅合法纯数字病区码漏出（输液看板默认病区落 sort 序首个合法项）
    expect(result).toEqual([wards[1]]);
  });

  it('listOrgs WARD：全部病区码非法时返回空数组（空清单由视图空态承载，零行漏出）', async () => {
    httpMock.get.mockResolvedValue(
      resp([
        { id: '1', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD' },
        { id: '2', orgCode: 'W02X', orgName: '异常病区', orgType: 'WARD' },
      ]),
    );

    const result = await listOrgs({ type: 'WARD' });

    expect(result).toEqual([]);
  });

  it('listOrgs DEPT：非纯数字科室码直通不过滤（DEPT-INT 系合法形态，防过度过滤哨兵）', async () => {
    const depts = [
      { id: '3', orgCode: 'DEPT-INT', orgName: '内科', orgType: 'DEPT' },
      { id: '4', orgCode: 'DEPT-SUR', orgName: '外科', orgType: 'DEPT' },
      { id: '5', orgCode: 'DEPT-PED', orgName: '儿科', orgType: 'DEPT' },
    ];
    httpMock.get.mockResolvedValue(resp(depts));

    const result = await listOrgs({ type: 'DEPT' });

    expect(result).toEqual(depts);
  });

  it('listOrgs：空 orgCode 行滤除（两档统一，出网值恒非空）', async () => {
    httpMock.get.mockResolvedValue(
      resp([
        { id: '1', orgCode: '', orgName: '无码病区', orgType: 'WARD' },
        { id: '2', orgCode: '1001', orgName: '一病区', orgType: 'WARD' },
      ]),
    );

    const wardResult = await listOrgs({ type: 'WARD' });
    expect(wardResult.map((org) => org.orgCode)).toEqual(['1001']);

    httpMock.get.mockResolvedValue(
      resp([
        { id: '3', orgCode: '', orgName: '无码科室', orgType: 'DEPT' },
        { id: '4', orgCode: 'DEPT-INT', orgName: '内科', orgType: 'DEPT' },
      ]),
    );

    const deptResult = await listOrgs({ type: 'DEPT' });
    expect(deptResult.map((org) => org.orgCode)).toEqual(['DEPT-INT']);
  });

  it('listOrgs WARD：过滤命中 warn 留痕（防「库里有、下拉无」排查黑洞）', async () => {
    const warnSpy = vi.spyOn(logger, 'warn').mockImplementation(() => {});
    httpMock.get.mockResolvedValue(
      resp([{ id: '1', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD' }]),
    );

    await listOrgs({ type: 'WARD' });

    expect(warnSpy).toHaveBeenCalledTimes(1);
    // 留痕含被滤 orgCode/orgName（机构信息非患者敏感数据，可入日志）
    expect(warnSpy).toHaveBeenCalledWith('listOrgs 病区码违反纯数字契约已过滤', 'W01', '演示病区');
    warnSpy.mockRestore();
  });

  it('listOrgs：data 为 null 时空数组兜底（与 listDictItems 既有兜底口径一致）', async () => {
    httpMock.get.mockResolvedValue(resp(null));

    const result = await listOrgs({ type: 'WARD' });

    expect(result).toEqual([]);
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
