import type { Directive } from 'vue';
import { useAuthStore } from '@/stores/auth';

/**
 * 元素权限指令（PR-4F F8 双入口之二，D-34 无码全隐藏）：mounted 时按会话权限集判定，
 * 无码值或无权限即 el.remove()（DOM 移除非置灰）；权限会话态静态（变更=踢出重登），
 * 无响应式重估需求——v-if 数据态条件后续挂载的元素经 mounted 钩子再次判定。
 */
export const permDirective: Directive<HTMLElement, string | undefined> = {
  mounted(el, binding) {
    if (binding.value === undefined || !useAuthStore().hasPerm(binding.value)) {
      el.remove();
    }
  },
};
