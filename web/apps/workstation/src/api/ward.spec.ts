// 病区视图域 API 层单测（暖纸卷宗丁-1 输液历史取用新增段）：mock http 模块断言出网路径
// 与返回直通、reject 冒泡（不打真实网络）。既有 wardCalls/coldChain/infusionBoard 资源组的
// 编排形态由视图层消费验证（InfusionBoardView.spec 承载业务链），拦截器链（弹错/traceId/401）
// 归 http.spec.ts 覆盖，本文件不重复。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { http } from './http';
import { getInfusionHistory } from './ward';

// http 单例以纯函数替身承载（api 层职责=路径与参数组装，无其他协作者；本文件仅触达 get）
vi.mock('./http', () => ({
  http: { get: vi.fn() },
}));

// 替身以数据属性形态取用（AxiosInstance 上的 get 为接口方法，直接引用触 unbound-method 误报）
const httpMock = http as unknown as { get: Mock };

/** 构造 axios 响应壳（api 层只消费 data 字段，其余字段以断言占位） */
function resp(data: unknown) {
  return { data, status: 200, statusText: 'OK', headers: {}, config: {} };
}

describe('病区视图域输液历史取用 API（丁-1 趋势抽屉读端点）', () => {
  beforeEach(() => {
    httpMock.get.mockReset();
  });

  it('getInfusionHistory：GET /v1/ward/infusion-history/{deviceId} 路径插值并直通返回', async () => {
    // 契约形态（InfusionHistoryVO 生成物）：设备号 + 余量/滴速双序列（遥测聚合点）
    const history = {
      deviceId: 'dev-yellow',
      remainSeries: [{ deviceId: 'dev-yellow', time: '2026-10-10T08:00:00Z', last: 420 }],
      dropSeries: [{ deviceId: 'dev-yellow', time: '2026-10-10T08:00:00Z', avg: 3.2 }],
    };
    httpMock.get.mockResolvedValue(resp(history));

    const result = await getInfusionHistory('dev-yellow');

    expect(httpMock.get).toHaveBeenCalledWith('/v1/ward/infusion-history/dev-yellow');
    expect(result).toBe(history);
  });

  it('getInfusionHistory 出网失败：reject 原样冒泡（api 层不吞错，统一出口归拦截器）', async () => {
    httpMock.get.mockRejectedValue(new Error('网络中断'));

    await expect(getInfusionHistory('dev-red')).rejects.toThrow('网络中断');
  });
});
