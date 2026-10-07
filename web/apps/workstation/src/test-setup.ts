// v-perm 元素权限指令的全局测试装配（评审 D-I1 一处收口）：main.ts 真实装配经 app.directive
// 全局注册，而既有 spec 的 mount 未注入 directives 时模板内 v-perm 会产生大量
// "Failed to resolve directive: perm" 警告噪音（WardBoardView.spec 实测单文件 86 条），
// 掩盖真实回归警告且与装配形态漂移。经 VTU 全局 config 对本应用全部 mount 生效。
//
// 为何不经 import 引入主线指令：setup 文件先于各 spec 的 vi.mock 执行，eager import
// '@/directives/perm' 会把 '@/stores/auth' → '@/api/http'/'@/api/auth'/'@/router' 的
// 真实模块提前固化进模块图，auth.spec/http.spec/LoginView.spec 等对同链路的 mock 即告
// 失效（实测 Network Error）——故此处按同语义就地读会话快照（sessionStorage 恢复链与
// stores/auth.loadFromStorage 同源同键），不触碰任何 app 模块导入。
//
// 语义对齐声明：判定式与 directives/perm.ts 完全一致（无码值或会话权限集不含即 el.remove，
// D-34 fail-closed；空集恒移除）。主线指令行为由 perm.spec.ts 与 30 视图的正反例用例
// （经 mountView 显式注入真实指令）双重锁定；本装配仅覆盖未注入的既有裸挂载。已显式注入
// directives 的 mount 不受影响（挂载选项优先于全局 config）。
// 经 vitest.config.ts test.setupFiles 挂载，对 workstation 全部 spec 生效。
import { config } from '@vue/test-utils';
import type { Directive } from 'vue';

/** auth store 会话持久化键（stores/auth.ts AUTH_STORAGE_KEY 同值，导入即污染故同源声明） */
const AUTH_STORAGE_KEY = 'fy:workstation:auth';

/** 读取当前会话快照的权限码集：与 loadFromStorage 同链路（缺失/损坏回空集=无权限，fail-closed） */
function readSessionPermissions(): string[] {
  try {
    const raw = sessionStorage.getItem(AUTH_STORAGE_KEY);
    if (raw === null) {
      return [];
    }
    const parsed: unknown = JSON.parse(raw);
    const permissions = (parsed as { user?: { permissions?: unknown } } | null)?.user?.permissions;
    return Array.isArray(permissions) ? (permissions as string[]) : [];
  } catch {
    // 快照损坏与未登录同口径：空集（D-34 fail-closed）
    return [];
  }
}

/** v-perm 测试装配指令：mounted 按会话权限集判定，无码值或不含即 DOM 移除（与主线指令同语义） */
const permTestDirective: Directive<HTMLElement, string | undefined> = {
  mounted(el, binding) {
    if (binding.value === undefined || !readSessionPermissions().includes(binding.value)) {
      el.remove();
    }
  },
};

// 全局注入 v-perm：合并而非整替换，保留其他可能的既有全局指令注册
config.global.directives = { ...config.global.directives, perm: permTestDirective };
