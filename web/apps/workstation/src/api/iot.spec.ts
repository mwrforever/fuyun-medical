// 物联网域 API 层单测（暖纸卷宗重设计丙-2 切片）：getIotDashboardSummary 未消费只读端点
// 取用（GET /iot/dashboard/summary——设备管理页门牌概览胶囊组四字段消费，零后端改动）的
// 契约路径与 data 直通断言（mock http 模块，不打真实网络）；products/devices 等存量资源组
// 出网行为已由消费方页面 spec 覆盖，本文件不重复。拦截器链归 http.spec.ts。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { http } from './http';
import { getIotDashboardSummary } from './iot';

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

describe('物联网域 API 层（概览汇总读端点取用）', () => {
  beforeEach(() => {
    httpMock.get.mockReset();
    httpMock.post.mockReset();
  });

  it('概览汇总 GET 契约路径 /v1/iot/dashboard/summary 且 data 直通（四计数 string 承载）', async () => {
    httpMock.get.mockResolvedValue(
      resp({ deviceTotal: '12', onlineCount: '8', offlineCount: '3', activeAlarmCount: '1' }),
    );

    const summary = await getIotDashboardSummary();

    expect(httpMock.get).toHaveBeenCalledWith('/v1/iot/dashboard/summary');
    expect(summary.deviceTotal).toBe('12');
    expect(summary.onlineCount).toBe('8');
    expect(summary.offlineCount).toBe('3');
    expect(summary.activeAlarmCount).toBe('1');
  });

  it('概览汇总出网失败时 promise reject（错误冒泡不吞，弹错归响应拦截器）', async () => {
    httpMock.get.mockRejectedValue(new Error('network down'));

    await expect(getIotDashboardSummary()).rejects.toThrow('network down');
  });
});
