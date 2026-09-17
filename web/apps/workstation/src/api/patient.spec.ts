// 患者域 API 层单测：mock http 模块断言路径与载荷透传（不打真实网络），覆盖预检 POST 路径/载荷、
// 建档 POST 路径、检索 GET 查询参数整体透传、详情路径插值、冻结/解冻两分支 POST 路径；
// 拦截器链（弹错/traceId/401）归 http.spec.ts 覆盖，本文件不重复。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { http } from './http';
import { changeFreeze, createPatient, getPatient, matchCheck, searchPatients } from './patient';

// http 单例以纯函数替身承载（api 层职责=路径与载荷组装，无其他协作者）
vi.mock('./http', () => ({
  http: { get: vi.fn(), post: vi.fn() },
}));

// 替身以数据属性形态取用（AxiosInstance 上的 get/post 为接口方法，直接引用触 unbound-method 误报）
const httpMock = http as unknown as { get: Mock; post: Mock };

/** 构造 axios 响应壳（api 层只消费 data 字段，其余字段以断言占位） */
function resp(data: unknown) {
  return { data, status: 200, statusText: 'OK', headers: {}, config: {} };
}

describe('患者域 API 层', () => {
  beforeEach(() => {
    httpMock.get.mockReset();
    httpMock.post.mockReset();
  });

  it('匹配预检 POST 契约路径并整体透传载荷（只读不落库）', async () => {
    httpMock.post.mockResolvedValue(resp({ outcome: 'NO_MATCH' }));
    const payload = { name: '张三', sex: '1' };

    const result = await matchCheck(payload);

    expect(httpMock.post).toHaveBeenCalledWith('/v1/patient/patients/match-check', payload);
    expect(result.outcome).toBe('NO_MATCH');
  });

  it('建档 POST 患者集合路径并透传请求体', async () => {
    httpMock.post.mockResolvedValue(resp({ outcome: 'NO_MATCH', candidatePatientId: 1 }));

    await createPatient({
      name: '张三',
      sex: '1',
      registerChannel: 'WINDOW',
      informedConsentRef: 'CONSENT-001',
    });

    expect(httpMock.post).toHaveBeenCalledWith('/v1/patient/patients', {
      name: '张三',
      sex: '1',
      registerChannel: 'WINDOW',
      informedConsentRef: 'CONSENT-001',
    });
  });

  it('检索 GET 契约路径并整体透传查询参数（page 为 0 基）', async () => {
    httpMock.get.mockResolvedValue(resp({ content: [], page: 0, size: 20, total: 0 }));

    const page = await searchPatients({ keyword: '13800000000', page: 2, size: 20 });

    expect(httpMock.get).toHaveBeenCalledWith('/v1/patient/patients/search', {
      params: { keyword: '13800000000', page: 2, size: 20 },
    });
    expect(page.total).toBe(0);
  });

  it('详情 GET 路径插值患者 id（雪花 ID 以 string 承载原样入路径）', async () => {
    httpMock.get.mockResolvedValue(resp({ patientId: '1932000000000000001', name: '张三' }));

    const vo = await getPatient('1932000000000000001');

    expect(httpMock.get).toHaveBeenCalledWith('/v1/patient/patients/1932000000000000001');
    expect(vo.name).toBe('张三');
  });

  it('冻结 POST freeze 路径携原因；解冻 POST unfreeze 路径无请求体（成对动作两分支）', async () => {
    httpMock.post.mockResolvedValue(resp(undefined));

    await changeFreeze('1932000000000000001', true, '违规查询冻结');
    expect(httpMock.post).toHaveBeenLastCalledWith(
      '/v1/patient/patients/1932000000000000001/freeze',
      {
        reason: '违规查询冻结',
      },
    );

    await changeFreeze('1932000000000000001', false);
    expect(httpMock.post).toHaveBeenLastCalledWith(
      '/v1/patient/patients/1932000000000000001/unfreeze',
    );
  });
});
