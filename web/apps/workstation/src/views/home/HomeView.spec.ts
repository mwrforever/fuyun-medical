// 岗位工作台首页单测：门牌页首（displayName/loginName 沿用 auth store，空会话防御兜底）、
// 常用入口链接条按「权限 ∩ 岗位」双道过滤（与侧栏同口径，入口经真实路由反查权限点）、
// 岗位经布局壳 POST_SELECTION_KEY 注入、空权限会话渲染诚实空态。挂载需 router
// （入口权限点经 router.resolve 反查），会话以直接注入 state 方式承载（假令牌资产）。
// 注：每用例新 Pinia 实例保证会话态互不串扰。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { computed } from 'vue';
import { beforeEach, describe, expect, it } from 'vitest';
import { router } from '@/router';
import { useAuthStore } from '@/stores/auth';
import { POST_SELECTION_KEY, type PostSelection } from '@/views/layout/menu';
import HomeView from './HomeView.vue';

describe('workstation 岗位工作台首页', () => {
  /** 文件级 Pinia：每用例新实例保证会话态互不串扰 */
  let pinia: Pinia;

  /**
   * 构造带权限点集的测试会话。
   *
   * @param permissions 权限点编码集；空数组 = 无任何业务权限的授权会话（PR-4D 全拒口径）
   */
  function injectSession(permissions: string[]): void {
    const auth = useAuthStore();
    auth.token = 'home-access-token'; // 测试假令牌资产，非真实凭证
    auth.user = {
      userId: '1',
      loginName: 'admin',
      displayName: '系统管理员',
      orgId: undefined,
      roles: ['ADMIN'],
      permissions,
    };
  }

  /**
   * 以指定岗位挂载首页：经布局壳注入键 provide 岗位值（computed 只读，与生产 provide
   * 形态一致）；缺省不 provide = 独立挂载防御场景（组件兜底「全部」）。
   *
   * @param post 岗位选择值；undefined 模拟无布局壳的直挂场景
   */
  function mountHome(post?: PostSelection) {
    return mount(HomeView, {
      global: {
        plugins: [pinia, router],
        ...(post !== undefined ? { provide: { [POST_SELECTION_KEY]: computed(() => post) } } : {}),
      },
    });
  }

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 预落登录页：首页消费路由表反查权限点，与当前停留路由解耦，只求路由器就绪
    await router.push('/login');
  });

  it('已登录渲染问候语、登录名与当前岗位标签（默认全部岗位）', () => {
    injectSession([]);
    const wrapper = mountHome();
    // 断言业务结果：问候区展示 displayName（时段词随钟点变，断言不绑钟点）+ loginName；
    // 岗位标签为真实 UI 态默认值
    expect(wrapper.text()).toContain('，系统管理员');
    expect(wrapper.text()).toContain('admin');
    expect(wrapper.text()).toContain('当前岗位：全部');
    wrapper.unmount();
  });

  it('入口链接条仅渲染会话权限内的真实路由入口（与侧栏同权限口径）', () => {
    injectSession(['nursing:ward:view', 'pharmacy:dispense:issue']);
    const wrapper = mountHome();
    const links = wrapper.findAll('a.home-quick-link');
    // 断言取入口名称标签（.home-quick-link-label），避免整条文本（缩写+名称）拼接干扰
    const labels = wrapper
      .findAll('a.home-quick-link .home-quick-link-label')
      .map((node) => node.text());
    // 集内权限入口可见；集外权限入口（挂号收费）隐藏
    expect(labels).toContain('护士站');
    expect(labels).toContain('发药工作台');
    expect(labels).not.toContain('挂号收费');
    expect(links).toHaveLength(2);
    wrapper.unmount();
  });

  it('岗位注入过滤链接条：药师岗位仅渲染药师口径入口（有权限但岗位不符者不出现）', () => {
    injectSession([
      'pharmacy:drug:maintain',
      'pharmacy:dispense:issue',
      'pharmacy:dispense:return',
      'pharmacy:review:audit',
      'pharmacy:dispense:inpatient',
      'nursing:ward:view',
    ]);
    const wrapper = mountHome('pharmacist');
    const labels = wrapper
      .findAll('a.home-quick-link .home-quick-link-label')
      .map((node) => node.text());
    // 药师口径五入口齐；护士站虽在权限集内但岗位不符，不出现；岗位标签同步
    expect(labels).toContain('药品字典');
    expect(labels).toContain('发药工作台');
    expect(labels).toContain('退药受理');
    expect(labels).toContain('住院审方台');
    expect(labels).toContain('住院摆药台');
    expect(labels).not.toContain('护士站');
    expect(wrapper.findAll('a.home-quick-link')).toHaveLength(5);
    expect(wrapper.text()).toContain('当前岗位：药师');
    wrapper.unmount();
  });

  it('空权限会话在具体岗位下渲染诚实空态（真实权限语义非占位文案）', () => {
    injectSession([]);
    const wrapper = mountHome('nurse');
    // 断言业务结果：权限∩岗位过滤后零入口 → 空态弱形态，链接条不渲染
    expect(wrapper.text()).toContain('该岗位暂无可视入口');
    expect(wrapper.find('a.home-quick-link').exists()).toBe(false);
    wrapper.unmount();
  });

  it('空会话兜底渲染未登录防御文案（直挂场景不渲染裸 undefined）', () => {
    const wrapper = mountHome();
    expect(wrapper.text()).toContain('，未登录用户');
    expect(wrapper.text()).not.toContain('undefined');
    wrapper.unmount();
  });
});
