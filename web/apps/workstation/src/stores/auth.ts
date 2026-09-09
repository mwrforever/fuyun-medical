/**
 * 认证会话 store（Pinia Setup Store，web B.3-1）：token/refreshToken/user 三态 +
 * sessionStorage 持久化（医疗工作站"换机即失效"语义：会话仅随浏览器标签页周期存活）。
 *
 * <p>出网一律经 api/auth（store 禁直连 axios）；组件外使用（路由守卫/拦截器回调内）
 * 均为运行时延迟调用，合规 web B.3-1。构造时完成两件事：从 sessionStorage 恢复会话、
 * 向 api/http 注册 401 未授权回调（清会话 + 回登录页，经回调解耦不反向依赖 router 之外
 * 的模块——router 为路由器单例非视图组件，不在 web B.2-3 禁令之列）。
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
  return (
    typeof candidate['token'] === 'string' &&
    typeof candidate['refreshToken'] === 'string' &&
    typeof user === 'object' &&
    user !== null
  );
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
  return { token, refreshToken, user, isLoggedIn, login, logout, loadFromStorage };
});
