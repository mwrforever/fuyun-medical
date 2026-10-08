// 侧栏权限过滤单测（BUG-14 守卫骨架）：权限点集为空=仅渲染无权限要求项（空集=无任何
// 权限全拒口径，PR-4D 语义反转）、集非空=仅渲染无权限要求项与集内项、过滤后清空的分组
// 整组剔除（防空标题组残留）；权限语义经真实路由表反查（路由 = 权限点清单），会话以直接
// 注入 state 的方式承载（假令牌资产，非真实凭证）。
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
   * @param permissions 权限点编码集；空数组 = 无任何业务权限的授权会话（后端 PR-4D 已
   *        填实登录契约，空集按无权限全拒消费），undefined 模拟权限字段缺省的防御会话形态
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

  it('权限点集为空（无任何权限）：仅显示未登记权限点的首页（与守卫同口径）', () => {
    injectSession([]);
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：空集=无任何业务权限（PR-4D 语义反转）——33 项菜单中仅首页（路由
    // 未登记权限点）可见，全部业务菜单隐藏；与守卫「已登记且集不含=拒绝」同一判定口径
    const texts = wrapper.findAll('.el-menu-item').map((item) => item.text());
    expect(texts).toEqual(['首页']);
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

  it('点击岗位切换器上行目标岗位事件（状态翻转归 MainLayout，组件不自持岗位态）', async () => {
    injectSession([]);
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：六枚 chip 齐备；点击「护士」上行 change 事件负载 'nurse'，
    // 点击前组件不发事件（翻转权在父布局）
    const chips = wrapper.findAll('button.app-sidebar-chip');
    expect(chips).toHaveLength(6);
    expect(wrapper.emitted('change')).toBeUndefined();
    const nurseChip = chips.find((chip) => chip.text() === '护士');
    expect(nurseChip).toBeDefined();
    await nurseChip?.trigger('click');
    expect(wrapper.emitted('change')).toEqual([['nurse']]);
    wrapper.unmount();
  });

  it('岗位 prop 过滤菜单：护士岗位仅渲染恒显项与归属护士且权限集内项', () => {
    injectSession(['nursing:ward:view']);
    const wrapper = mount(AppSidebar, {
      global: { plugins: [pinia, router] },
      props: { post: 'nurse' },
    });

    // 断言业务结果：权限 ∩ 岗位双道过滤后仅剩首页（恒显）+ 护士站（护士口径且集内）；
    // 患者检索（集内但岗位不符）与发药工作台（无权限）均不渲染
    const texts = wrapper.findAll('.el-menu-item').map((item) => item.text());
    expect(texts).toEqual(['首页', '护士站']);
    // 切换器选中态随 prop 反白（亮纸白药丸挂 is-active）
    const activeChip = wrapper.find('button.app-sidebar-chip.is-active');
    expect(activeChip.text()).toBe('护士');
    wrapper.unmount();
  });

  it('折叠态保留当前岗位单 chip：单字呈现全名可读，点击循环切换至下一岗位', async () => {
    injectSession([]);
    const wrapper = mount(AppSidebar, {
      global: { plugins: [pinia, router] },
      props: { collapsed: true, post: 'nurse' },
    });

    // 断言业务结果：折叠态仅一枚岗位 chip（护士岗位单字「护」），aria-label 携带全名
    // 供读屏识别；点击沿选项数组序上行下一岗位（护士 → 药师），翻转权归父布局
    const chips = wrapper.findAll('button.app-sidebar-chip');
    expect(chips).toHaveLength(1);
    expect(chips[0]?.text()).toBe('护');
    expect(chips[0]?.attributes('aria-label')).toBe('切换岗位，当前护士');
    await chips[0]?.trigger('click');
    expect(wrapper.emitted('change')).toEqual([['pharmacist']]);
    wrapper.unmount();
  });

  it('折叠态岗位 chip 循环边界：设备科点击回「全部」岗位', async () => {
    injectSession([]);
    const wrapper = mount(AppSidebar, {
      global: { plugins: [pinia, router] },
      props: { collapsed: true, post: 'equipment' },
    });

    // 断言业务结果：循环序收口——末位岗位（设备科「设」）点击后回到「全部」
    const chip = wrapper.find('button.app-sidebar-chip');
    expect(chip.text()).toBe('设');
    await chip.trigger('click');
    expect(wrapper.emitted('change')).toEqual([['all']]);
    wrapper.unmount();
  });
});
