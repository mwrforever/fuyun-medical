// portal Axios 单例单测（免登录匿名通道）：自定义 adapter 承载响应（禁打真实网络），
// 断言请求头【无 Authorization】+ X-Trace-Id 唯一注入、ProblemDetail 归一化（detail/errorCode/
// status 三锚点）、网络层错误回退通用文案与 status=0。
import { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { http, PortalApiError } from './http';

/** 构造携带响应体的 AxiosError，供自定义 adapter 抛出以模拟非 2xx（data 模拟 ProblemDetail） */
function axiosErrorWith(status: number, data: unknown): AxiosError {
  const config = {} as InternalAxiosRequestConfig;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, null, {
    data,
    status,
    statusText: 'Error',
    headers: {},
    config,
  } as AxiosResponse);
}

describe('portal Axios 单例（免登录通道）', () => {
  beforeEach(() => {
    // 每用例重置 adapter，防跨用例捕获残留
    http.defaults.adapter = undefined;
  });

  it('匿名通道零 Authorization 注入（令牌面不存在于 portal），X-Trace-Id 每请求唯一', async () => {
    const captured: Array<{ authorization: string; traceId: string }> = [];
    http.defaults.adapter = (config) => {
      captured.push({
        authorization: String(config.headers?.['Authorization'] ?? ''),
        traceId: String(config.headers?.['X-Trace-Id'] ?? ''),
      });
      return Promise.resolve({
        data: {},
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse);
    };

    await http.get('/ping');
    await http.get('/ping');

    // 断言业务结果：免登录通道任何情况下不出现 Authorization 头（裁决 13 匿名语义）
    expect(captured[0]?.authorization).toBe('');
    expect(captured[1]?.authorization).toBe('');
    expect(captured[0]?.traceId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(captured[1]?.traceId).not.toBe(captured[0]?.traceId);
  });

  it('非 2xx 归一化为 PortalApiError：detail/errorCode/status 三锚点齐备', async () => {
    http.defaults.adapter = () =>
      Promise.reject(
        axiosErrorWith(409, {
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          detail: '号源池余量不足',
          properties: { errorCode: 'OP-1003', traceId: 't-409' },
        }),
      );

    const error = await http.get('/v1/outpatient/portal/appointments').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(PortalApiError);
    expect((error as PortalApiError).detail).toBe('号源池余量不足');
    expect((error as PortalApiError).errorCode).toBe('OP-1003');
    expect((error as PortalApiError).status).toBe(409);
  });

  it('响应体缺 errorCode 时为 null，detail 缺失回退通用文案；网络层错误 status=0', async () => {
    // 5xx 无 properties：errorCode null、detail 兜底
    http.defaults.adapter = () =>
      Promise.reject(axiosErrorWith(502, { title: 'Bad Gateway', status: 502 }));
    const gatewayError = (await http.get('/ping').catch((e: unknown) => e)) as PortalApiError;
    expect(gatewayError).toBeInstanceOf(PortalApiError);
    expect(gatewayError.errorCode).toBeNull();
    expect(gatewayError.detail).toBe('网络异常，请稍后重试');
    expect(gatewayError.status).toBe(502);

    // 网络层错误（无响应体）：status=0
    http.defaults.adapter = () => Promise.reject(new AxiosError('Network Error'));
    const networkError = (await http.get('/ping').catch((e: unknown) => e)) as PortalApiError;
    expect(networkError).toBeInstanceOf(PortalApiError);
    expect(networkError.status).toBe(0);
    expect(networkError.detail).toBe('网络异常，请稍后重试');
  });
});
