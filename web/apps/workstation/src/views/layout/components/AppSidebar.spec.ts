// 侧栏权限过滤单测（BUG-14 守卫骨架）：权限点集为空=全量显示（数据源缺失口径）、集非空=
// 仅渲染无权限要求项与集内项、过滤后清空的分组整组剔除（防空标题组残留）；权限语义经真实
// 路由表反查（路由 = 权限点清单），会话以直接注入 state 的方式承载（假令牌资产，非真实凭证）。
// 注：全文件共享单一 Pinia——与挂载组件必须读写同一会话 store，逐用例换实例会割裂会话语义。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import { router } from '@/router';
import { useAuthStore } from '@/stores/auth';
import AppSidebar from './AppSidebar.vue';

describe('侧栏权限过滤', () => {
  /** 文件级共享 Pinia：被挂组件与用例注入的会话必须同一 store 实例 */
  let pinia: Pinia;

  /**
   * 构造带权限点集的测试会话。
   *
   * @param permissions 权限点编码集；undefined 模拟 P0 后端契约未返回该字段的数据源缺失形态
   */
  function injectSession(permissions: string[] | undefined): void {
    const auth = useAuthStore();
    auth.token = 'sidebar-access-token';
    auth.user = {
      userId: '1',
      loginName: 'nurse01',
      displayName: '测试护士',
      orgId: undefined,
      roles: [],
      ...(permissions !== undefined ? { permissions } : {}),
    };
  }

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 预落登录页：侧栏挂载仅消费路由表与会话，与当前停留路由解耦，此处只求路由器就绪
    await router.push('/login');
  });

  it('权限点集缺失（空集）：全量显示 33 项菜单（与守卫全放行同口径）', () => {
    injectSession(undefined);
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：数据源缺失时侧栏不做任何过滤——全量 33 项（与 MENU_ITEMS 常量同数，
    // 新增菜单项时随动更新），各分组与顶层项均渲染
    expect(wrapper.findAll('.el-menu-item')).toHaveLength(33);
    const texts = wrapper.findAll('.el-menu-item').map((item) => item.text());
    expect(texts).toContain('首页');
    expect(texts).toContain('退费审批');
    expect(texts).toContain('PDA 扫码');
    expect(texts).toContain('冷链台账');
    expect(texts).toContain('住院摆药台');
    wrapper.unmount();
  });

  it('权限点集非空：仅渲染无权限要求项与集内项，集外项隐藏', () => {
    injectSession(['patient:archive:search']);
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：首页（未登记权限点）恒可见；患者检索（集内权限点）可见；
    // 患者建档/退费审批（集外权限点）隐藏
    const texts = wrapper.findAll('.el-menu-item').map((item) => item.text());
    expect(texts).toContain('首页');
    expect(texts).toContain('患者检索');
    expect(texts).not.toContain('患者建档');
    expect(texts).not.toContain('退费审批');
    expect(wrapper.findAll('.el-menu-item')).toHaveLength(2);
    wrapper.unmount();
  });

  it('组内菜单项全部被滤时整组剔除，部分可见时保留分组', () => {
    injectSession(['patient:archive:search', 'pharmacy:dispense:issue']);
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：药房管理组仅剩发药工作台（集内）而药品字典/住院审方台被滤，分组保留；
    // 护理管理组两项（护士站/PDA 扫码）全部被滤，整组剔除不渲染空标题组
    const texts = wrapper.findAll('.el-menu-item').map((item) => item.text());
    expect(texts).toContain('发药工作台');
    expect(texts).not.toContain('药品字典');
    expect(texts).not.toContain('住院审方台');
    expect(wrapper.text()).toContain('药房管理');
    expect(wrapper.text()).not.toContain('护理管理');
    // 可见项 = 首页 + 患者检索 + 发药工作台
    expect(wrapper.findAll('.el-menu-item')).toHaveLength(3);
    wrapper.unmount();
  });
});
