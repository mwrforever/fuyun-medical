/**
 * 认证会话 store（Pinia Setup Store，web B.3-1）：token/refreshToken/user 三态 +
 * sessionStorage 持久化（医疗工作站"换机即失效"语义：会话仅随浏览器标签页周期存活）。
 *
 * <p>出网一律经 api/auth（store 禁直连 axios）；组件外使用（路由守卫/拦截器回调内）
 * 均为运行时延迟调用，合规 web B.3-1。构造时完成两件事：从 sessionStorage 恢复会话、
 * 向 api/http 注册 401 未授权回调（清会话 + 回登录页，经回调解耦不反向依赖 router 之外
 * 的模块——router 为路由器单例非视图组件，不在 web B.2-3 禁令之列）。
 *
 * <p>权限点集（user.permissions 派生）与 hasRoutePermission 三态判定供路由守卫与侧栏
 * 过滤共用（BUG-14 守卫骨架，单一口径防两处漂移）；数据源随 P1 鉴权接线补齐。
 */
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { login as loginApi, logout as logoutApi } from '@/api/auth';
import { setUnauthorizedHandler } from '@/api/http';
import { router } from '@/router';
import type { LoginRequest, LoginResponse, UserVO } from '@/types/auth';

/** sessionStorage 持久化键（冒号分层，与后端 Redis 键规范风格一致） */
const AUTH_STORAGE_KEY = 'fy:workstation:auth';

/** sessionStorage 持久化快照结构（与 state 一一对应，读写均经守卫收窄） */
interface AuthSnapshot {
  token: string;
  refreshToken: string;
  user: UserVO;
}

/**
 * 权限点清单结构守卫：纯字符串数组（逐项收窄）。
 *
 * @param value sessionStorage 快照 user.permissions 字段（unknown）
 * @return 结构合法返回 true 并收窄类型
 */
function isPermissionList(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

/**
 * 快照结构类型守卫：sessionStorage 属可被用户篡改的外部存储，读入前逐字段收窄，
 * 防脏数据污染会话 state（禁 any 口径下的 unknown 收窄样板）。
 *
 * @param value JSON.parse 产物（unknown）
 * @return 结构合法返回 true 并收窄类型
 */
function isAuthSnapshot(value: unknown): value is AuthSnapshot {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  const user = candidate['user'];
  if (
    typeof candidate['token'] !== 'string' ||
    typeof candidate['refreshToken'] !== 'string' ||
    typeof user !== 'object' ||
    user === null
  ) {
    return false;
  }
  // permissions 为可选字段：存在时必须是纯字符串数组——非数组脏数据（如字符串）会让
  // 权限判定退化成子串匹配，整份快照丢弃回未登录态（与损坏 JSON 同口径）
  const permissions = (user as Record<string, unknown>)['permissions'];
  return permissions === undefined || isPermissionList(permissions);
}

export const useAuthStore = defineStore('auth', () => {
  /** 访问令牌（typ=access），null = 未登录 */
  const token = ref<string | null>(null);
  /** 刷新令牌（typ=refresh），与 access 同会话（P0 不做轮换，暂存备用） */
  const refreshToken = ref<string | null>(null);
  /** 登录用户身份（显示名供顶栏用户区、roles 供 P1 鉴权） */
  const user = ref<UserVO | null>(null);

  /** 是否已登录：路由守卫默认拒绝与顶栏用户区共用的判定口径 */
  const isLoggedIn = computed(() => token.value !== null);

  /**
   * 权限点编码集合（user.permissions 派生，登录/恢复/清空随会话身份自动同步）：
   * 空集 = 数据源缺失（P0 后端契约未返回 permissions），守卫与侧栏按骨架语义全放行/全量显示。
   */
  const permissions = computed<string[]>(() => user.value?.permissions ?? []);

  /**
   * 路由权限判定（路由守卫与侧栏过滤共用的三态口径，BUG-14 守卫骨架）：
   * 1. 路由未登记权限点（permission 缺省，如首页/登录页）→ 放行；
   * 2. 权限点集为空 → 全放行；
   * 3. 权限点集非空且不含目标权限点 → 拒绝（守卫重定向 403，侧栏隐藏对应菜单项）。
   *
   * @param permission 目标路由 meta.permission 权限点编码；缺省 = 路由未登记权限语义
   * @return true 放行；false 拒绝（仅「集非空且不含」一种形态）
   */
  function hasRoutePermission(permission: string | undefined): boolean {
    if (permission === undefined) {
      return true;
    }
    // TODO(P1-authz): 权限点数据源缺失（后端登录契约未返回 permissions）暂按全放行；
    // P1 鉴权接线补齐数据源后，需收紧为「空集 = 无任何权限」的严格拒绝语义，计划于 P1 引入
    if (permissions.value.length === 0) {
      return true;
    }
    return permissions.value.includes(permission);
  }

  /** 从 sessionStorage 恢复会话（标签页刷新后保活；损坏数据丢弃并清残留键） */
  function loadFromStorage(): void {
    const raw = sessionStorage.getItem(AUTH_STORAGE_KEY);
    if (raw === null) {
      return;
    }
    try {
      const parsed: unknown = JSON.parse(raw);
      if (isAuthSnapshot(parsed)) {
        token.value = parsed.token;
        refreshToken.value = parsed.refreshToken;
        user.value = parsed.user;
      }
    } catch {
      // 非法 JSON 视为无会话：清掉残留键，避免每次构造重复解析失败
      sessionStorage.removeItem(AUTH_STORAGE_KEY);
    }
  }

  /** 会话快照写回 sessionStorage（登录成功后调用；禁落 localStorage 的跨站持久态） */
  function persistSession(snapshot: AuthSnapshot): void {
    sessionStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(snapshot));
  }

  /** 清空会话（登出与 401 未授权共用出口）：三态归 null + 存储键移除 */
  function clearSession(): void {
    token.value = null;
    refreshToken.value = null;
    user.value = null;
    sessionStorage.removeItem(AUTH_STORAGE_KEY);
  }

  /** 回登录页：已在登录页则跳过（防登录失败 401 场景的重复导航）；导航中断静默忽略 */
  async function navigateToLogin(): Promise<void> {
    if (router.currentRoute.value.path === '/login') {
      return;
    }
    try {
      await router.push({ path: '/login' });
    } catch {
      // 守卫取消/重复导航等中断失败无业务影响，吞掉避免未处理 Promise 拒绝
    }
  }

  /**
   * 登录：调 api → 写 state → 持久化 sessionStorage。
   *
   * @param credentials 登录凭据，非空；失败向上抛（错误提示已由拦截器统一弹出）
   */
  async function login(credentials: LoginRequest): Promise<void> {
    const resp: LoginResponse = await loginApi(credentials);
    const snapshot: AuthSnapshot = {
      token: resp.accessToken,
      refreshToken: resp.refreshToken,
      user: resp.user,
    };
    token.value = snapshot.token;
    refreshToken.value = snapshot.refreshToken;
    user.value = snapshot.user;
    persistSession(snapshot);
  }

  /** 登出：api 失败忽略（拦截器已提示，本地会话必须清理）→ 清 state → 回登录页 */
  async function logout(): Promise<void> {
    try {
      await logoutApi();
    } catch {
      // 服务端会话可能已失效/网络不可达：不阻断本地登出（医疗终端换机即失效语义优先）
    }
    clearSession();
    // 等待跳转完成（含懒加载登录页导入），保证调用方 await logout() 后路由已就位
    await navigateToLogin();
  }

  // 构造时恢复会话 + 注册 401 统一出口（回调运行时 pinia 必已激活，web B.3-1 延迟调用口径）
  loadFromStorage();
  setUnauthorizedHandler(() => {
    clearSession();
    void navigateToLogin();
  });

  // Setup Store 必须返回全部 state（web B.3-1），保证 storeToRefs 解构可用
  return {
    token,
    refreshToken,
    user,
    isLoggedIn,
    permissions,
    hasRoutePermission,
    login,
    logout,
    loadFromStorage,
  };
});
