// v-perm 元素权限指令单测（PR-4F F8 Task 8）：mounted 按会话权限集判定 DOM 保留/移除
// （D-34 全隐藏）；会话经 sessionStorage 播种驱动 + setActivePinia 激活——auth store
// 构造期恢复，免引入测试专用 pinia
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import { defineComponent } from 'vue';
import { permDirective } from './perm';

/**
 * 挂载带 v-perm 的按钮组件（指令与 pinia 经 mount 全局选项注入，聚焦指令自身行为断言）。
 * 按钮必须包在容器 div 内作后代节点：VTU 的 find 对组件根节点走 vnode 树匹配（不查
 * live DOM），被 remove 的根元素仍会被 exists 找到——后代节点才能真实观测 DOM 移除。
 */
function mountWithDirective(code: string | undefined, pinia: ReturnType<typeof createPinia>) {
  return mount(
    defineComponent({
      template: '<div><button v-perm="code">按钮</button></div>',
      data: () => ({ code }),
    }),
    { global: { plugins: [pinia], directives: { perm: permDirective } } },
  );
}

describe('v-perm 元素权限指令', () => {
  beforeEach(() => {
    // 用例间隔离：清会话种子防跨用例污染（pinia 由用例内重建激活，照 auth.spec.ts 口径）
    sessionStorage.clear();
  });

  it('v-perm：会话含码保留 DOM，不含码移除 DOM，无码值移除 DOM（D-34 全隐藏）', () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['pharmacy:dispense:btn:issue'] },
      }),
    );
    const pinia = createPinia();
    // 指令内 useAuthStore() 需激活态 pinia（auth store 构造期从 sessionStorage 恢复会话）
    setActivePinia(pinia);
    expect(mountWithDirective('pharmacy:dispense:btn:issue', pinia).find('button').exists()).toBe(
      true,
    );
    expect(mountWithDirective('billing:refund:btn:approve', pinia).find('button').exists()).toBe(
      false,
    );
    // fail-closed：无码值全隐藏
    expect(mountWithDirective(undefined, pinia).find('button').exists()).toBe(false);
  });
});
