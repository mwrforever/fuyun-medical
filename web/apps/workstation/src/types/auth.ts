/**
 * 认证域手写后备类型（web A.3-3 后备条款）。
 *
 * ⚠️ openapi-typescript 生成物可用后由 packages/shared api.d.ts 承接并删除本文件
 * （T-R4-3 演练后启动生成链路，PR-3 P0 期间为本文件有效期）。
 * 字段名与后端契约逐字对齐：backend/fuyun-system 的 dto/LoginRequest、vo/LoginResponse、vo/UserVO；
 * userId/orgId 为后端 Long，经全局 Long→String 以 JSON 字符串输出，前端一律 string 承载（web A.3-6）。
 */

/** 登录请求体（POST /api/v1/system/auth/login） */
export interface LoginRequest {
  /** 登录名（sys_user.login_name），非空；来源：用户输入 */
  loginName: string;
  /** 口令明文，仅请求期存在；敏感字段禁止落日志 */
  password: string;
}

/** 登录用户身份（LoginResponse.user 字段） */
export interface UserVO {
  /** 用户 ID（sys_user.id 雪花 ID 的十进制字符串），非空 */
  userId: string;
  /** 登录名，非空 */
  loginName: string;
  /** 显示名（员工姓名，无员工行以登录名兜底），非空；前端用户区展示 */
  displayName: string;
  /** 主归属机构 ID（雪花 ID 字符串），可 null（P0 种子不建机构行） */
  orgId: string | null;
  /** 角色编码清单，非 null（无角色为空清单）；P1 鉴权拦截的数据来源 */
  roles: string[];
}

/** 登录/刷新成功响应（POST /api/v1/system/auth/login 与 /refresh 双端点同构） */
export interface LoginResponse {
  /** 访问令牌（typ=access），非空 */
  accessToken: string;
  /** 刷新令牌（typ=refresh），非空；与 access 同会话，登出即双令牌同失效 */
  refreshToken: string;
  /** 令牌方案名，恒为 "Bearer"（RFC 6750） */
  tokenType: string;
  /** access 令牌有效期（秒）；后端为原生 long，经全局 Long→String 以 JSON 字符串输出，前端字符串承载 */
  expiresIn: string;
  /** 登录用户身份，非空 */
  user: UserVO;
}
