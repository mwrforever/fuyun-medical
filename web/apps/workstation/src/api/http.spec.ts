// Axios 单例单测（BRIEF-PR3-01 §4）：以自定义 adapter 承载响应（禁打真实网络），
// 断言请求头注入、ProblemDetail 提示提取、401 未授权回调三类拦截器行为；ElMessage 以 mock 承载
import { createPinia, setActivePinia } from 'pinia';
import { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { http, setUnauthorizedHandler } from './http';
import { useAuthStore } from '@/stores/auth';

// 统一 mock ElMessage（拦截器错误出口依赖），断言弹错文案而非真实弹窗
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }));

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

describe('Axios 单例拦截器', () => {
  beforeEach(() => {
    // 拦截器回调内延迟调用 useAuthStore（web B.3-1）：先激活 pinia 并完成 store 构造（注册默认未授权回调）
    setActivePinia(createPinia());
    useAuthStore();
  });

  it('请求拦截器注入 Bearer 令牌与每请求唯一 X-Trace-Id，并透出响应 X-Trace-Id 头', async () => {
    const auth = useAuthStore();
    auth.token = 'it-access-token';
    const captured: Array<{ authorization: string; traceId: string }> = [];
    // 自定义 adapter：捕获请求头并回显 X-Trace-Id 响应头（后端 TraceIdFilter 回写行为的替身）
    http.defaults.adapter = (config) => {
      const authorization = String(config.headers?.['Authorization'] ?? '');
      const traceId = String(config.headers?.['X-Trace-Id'] ?? '');
      captured.push({ authorization, traceId });
      return Promise.resolve({
        data: {},
        status: 200,
        statusText: 'OK',
        headers: { 'x-trace-id': traceId },
        config,
      } as AxiosResponse);
    };

    const first = await http.get('/ping');
    const second = await http.get('/ping');

    // 断言业务结果：令牌以 Bearer 方案注入（后端 AuthTokenInterceptor 解析口径）
    expect(captured[0]?.authorization).toBe('Bearer it-access-token');
    // traceId 为 uuid v4 形态且每请求唯一（后端 TraceIdFilter 复用为全链路锚点）
    expect(captured[0]?.traceId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(captured[1]?.traceId).not.toBe(captured[0]?.traceId);
    // 后端回写的 X-Trace-Id 响应头经 axios 原样透出，供前后端 traceId 对账
    expect(first.headers['x-trace-id']).toBe(captured[0]?.traceId);
    expect(second.headers['x-trace-id']).toBe(captured[1]?.traceId);
  });

  it('未携带令牌（未登录）时不注入 Authorization 头', async () => {
    let authorization: string | undefined;
    http.defaults.adapter = (config) => {
      const raw = config.headers?.['Authorization'];
      authorization = raw === undefined ? undefined : String(raw);
      return Promise.resolve({
        data: {},
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse);
    };

    await http.get('/ping');

    expect(authorization).toBeUndefined();
  });

  it('非 2xx 响应统一提示 ProblemDetail.detail 且不触发未授权回调', async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    http.defaults.adapter = () =>
      Promise.reject(
        axiosErrorWith(500, {
          type: 'about:blank',
          title: 'Internal Server Error',
          status: 500,
          detail: '字典类型不存在',
          errorCode: 'SYS-1011',
          traceId: 't-500',
        }),
      );

    await expect(http.get('/v1/system/dicts/nope')).rejects.toBeInstanceOf(AxiosError);

    // 断言业务结果：错误文案取自 ProblemDetail.detail；非 401 不触发登出回调
    expect(ElMessage.error).toHaveBeenCalledWith('字典类型不存在');
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('401 响应触发注册的未授权回调并提示 detail 文案', async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    http.defaults.adapter = () =>
      Promise.reject(
        axiosErrorWith(401, {
          type: 'about:blank',
          title: 'Unauthorized',
          status: 401,
          detail: '令牌已过期',
          errorCode: 'SYS-1004',
          traceId: 't-401',
        }),
      );

    await expect(http.get('/v1/system/practice/check')).rejects.toBeInstanceOf(AxiosError);

    // 断言业务结果：401 触发清会话 + 回登录页的回调（注册方为 auth store，此处以 spy 断言触发）
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(ElMessage.error).toHaveBeenCalledWith('令牌已过期');
  });

  it('错误响应体缺失 detail 时回退统一文案「请求失败」', async () => {
    setUnauthorizedHandler(vi.fn());
    http.defaults.adapter = () => Promise.reject(axiosErrorWith(502, null));

    await expect(http.get('/ping')).rejects.toBeInstanceOf(AxiosError);

    expect(ElMessage.error).toHaveBeenCalledWith('请求失败');
  });
});
