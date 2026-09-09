/**
 * 认证域 API（web A.3-5 按业务域模块化）：login / refresh / logout 三操作。
 * 路径与后端契约对齐：POST /api/v1/system/auth/*（baseURL 已含 /api 前缀）；
 * 请求与响应分别建模（LoginRequest / LoginResponse），手写后备类型见 types/auth.ts。
 */
import { http } from './http';
import type { LoginRequest, LoginResponse } from '@/types/auth';

/**
 * 登录：登录名 + 口令换双令牌与用户身份。
 *
 * @param payload 登录凭据（loginName/password），非空；口令仅经本函数出网，禁止落日志
 * @return 登录响应（accessToken/refreshToken/expiresIn/user）；失败由响应拦截器统一弹错并上抛
 */
export async function login(payload: LoginRequest): Promise<LoginResponse> {
  const resp = await http.post<LoginResponse>('/v1/system/auth/login', payload);
  return resp.data;
}

/**
 * 刷新：refresh 令牌换发新 access 令牌（同会话，refresh 值不轮换）。
 *
 * @param refreshToken 刷新令牌原文（typ=refresh），非空；来源：登录响应留存的会话存储
 * @return 登录响应（新 accessToken + 原 refreshToken + 用户身份）；无效刷新 401 SYS-1005
 */
export async function refresh(refreshToken: string): Promise<LoginResponse> {
  const resp = await http.post<LoginResponse>('/v1/system/auth/refresh', { refreshToken });
  return resp.data;
}

/**
 * 登出：删除当前令牌会话（access 与 refresh 同 sid 同时失效），成功为 204 无响应体。
 *
 * @throws 经响应拦截器弹错后上抛；调用方（auth store）按"本地登出必须完成"语义忽略失败
 */
export async function logout(): Promise<void> {
  await http.post('/v1/system/auth/logout');
}
