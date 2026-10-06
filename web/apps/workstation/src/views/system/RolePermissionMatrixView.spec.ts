// 权限管理台矩阵编辑器单测（PR-4F Task 9）：角色 tab 渲染与 ADMIN 只读注记（D3）、
// ELEMENT 勾选态=permCodes 交集、保存全量覆写语义（含未变部分+清单外码带回）与
// 「重新登录生效」提示（F4③）、启停开关 DISABLED 二次确认（含取消不执行）、
// listPermissions 失败空态兜底不崩。api mock 承载，不打真实网络；sessionStorage
// 播种 admin 会话（含 system:permission:manage，页面守卫依赖的运行环境保真）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { MessageBoxData } from 'element-plus';
import { listPermissions, listRoles, updateRolePermissions, updateRoleStatus } from '@/api/system';
import type { PermissionGroupVO, RoleAdminVO } from '@/api/system';
import RolePermissionMatrixView from './RolePermissionMatrixView.vue';

vi.mock('@/api/system', () => ({
  listRoles: vi.fn(),
  listPermissions: vi.fn(),
  updateRolePermissions: vi.fn(),
  updateRoleStatus: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox.confirm（保存提示断言与停用确认框可控），其余导出原样保留
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), success: vi.fn(), error: vi.fn() },
    ElMessageBox: { ...mod.ElMessageBox, confirm: vi.fn() },
  };
});

/** 构造角色行（permCodes 由各用例指定以驱动勾选态断言；status 驱动启停开关断言） */
function roleRow(roleCode: string, roleName: string, permCodes: string[]): RoleAdminVO {
  return {
    roleCode,
    roleName,
    status: 'ACTIVE',
    dataScopeType: 'ALL',
    permCodes,
  };
}

/** 权限点分组清单（三类型各一小组：MENU 组含护士站菜单码、ELEMENT 组含护理与收费域码） */
function permissionGroups(): PermissionGroupVO[] {
  return [
    {
      permType: 'API',
      points: [{ permCode: 'GET /api/v1/billing/fees', permName: '费用查询端点' }],
    },
    {
      permType: 'MENU',
      points: [{ permCode: 'nursing:ward:view', permName: '护士站菜单' }],
    },
    {
      permType: 'ELEMENT',
      points: [
        { permCode: 'nursing:ward:btn:vital', permName: '病区看板-体温录入按钮' },
        { permCode: 'nursing:ward:btn:task', permName: '病区看板-任务流转按钮' },
        { permCode: 'billing:refund:btn:approve', permName: '退费审批-审批按钮' },
      ],
    },
  ];
}

/** NURSE 场景角色集（ADMIN 首位=种子序，默认激活 tab 即 ADMIN；NURSE 含元素码绑定） */
function nurseScenarioRoles(): RoleAdminVO[] {
  return [
    roleRow('ADMIN', '系统管理员', []),
    roleRow(
      'NURSE',
      '护士',
      // nursing:execution:btn:perform 不在分组清单内=清单外码（保存带回断言面），
      // 其余三码分别落 MENU 与 ELEMENT 组（交集初始化断言面）
      ['nursing:ward:view', 'nursing:ward:btn:task', 'nursing:execution:btn:perform'],
    ),
  ];
}

/** 点击 el-tabs 页签（按页签文本匹配，双层 tabs 各自定位） */
async function clickTab(wrapper: VueWrapper, tabsClass: string, text: string): Promise<void> {
  const tab = wrapper
    .findAll(`.${tabsClass} .el-tabs__item`)
    .find((item) => item.text().includes(text));
  if (tab === undefined) {
    throw new Error(`未找到页签：${text}`);
  }
  await tab.trigger('click');
}

/** 定位角色 pane（EP tab-pane 全量渲染 DOM，须按角色码收窄到本 pane 内断言/操作；
 * 顶层 role-tabs 直接子 pane 才是角色层，嵌套 type-tabs 的 pane 不混入） */
function rolePane(wrapper: VueWrapper, roleCode: string) {
  const pane = wrapper
    .findAll('.role-tabs > .el-tabs__content > .el-tab-pane')
    .find((item) => item.find('.role-code').text() === roleCode);
  if (pane === undefined) {
    throw new Error(`未找到角色 pane：${roleCode}`);
  }
  return pane;
}

/** 按权限码在指定角色 pane 内定位 checkbox input（勾选态断言共用入口） */
function checkboxOf(wrapper: VueWrapper, roleCode: string, permCode: string) {
  return rolePane(wrapper, roleCode).find(`input[type="checkbox"][value="${permCode}"]`);
}

/** 按按钮文案点击（patient/billing 三页 spec 同款） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (button === undefined) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('权限管理台矩阵编辑器', () => {
  beforeEach(() => {
    vi.mocked(listRoles).mockReset();
    vi.mocked(listPermissions).mockReset();
    vi.mocked(updateRolePermissions).mockReset();
    vi.mocked(updateRoleStatus).mockReset();
    vi.mocked(ElMessage.success).mockReset();
    vi.mocked(ElMessageBox.confirm).mockReset();
    sessionStorage.clear();
    // 播种 admin 会话（页面运行环境保真：路由守卫消费的会话权限集含管理台菜单码）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-access-token',
        refreshToken: 'test-refresh-token',
        user: {
          userId: '1',
          loginName: 'admin',
          displayName: '系统管理员',
          orgId: null,
          roles: ['ADMIN'],
          permissions: ['system:permission:manage'],
        },
      }),
    );
    // 默认装载 NURSE 场景（各用例按需覆写），防未 stub resolve 断链
    vi.mocked(listRoles).mockResolvedValue(nurseScenarioRoles());
    vi.mocked(listPermissions).mockResolvedValue(permissionGroups());
  });

  it('角色 tab 渲染自 listRoles，ADMIN 默认激活且矩阵区只读并注记运行期全放', async () => {
    const wrapper = mount(RolePermissionMatrixView);
    await flushPromises();

    // 双角色 tab 均渲染（ADMIN 首位默认激活——种子序）
    expect(wrapper.text()).toContain('系统管理员');
    expect(wrapper.text()).toContain('护士');
    // ADMIN 只读注记（D3）：运行期全放语义对管理员显式说明
    expect(wrapper.text()).toContain('ADMIN 运行期全放');
    // ADMIN pane 矩阵 checkbox 区只读（无绑定行可编辑；switch 隐藏 input 同随禁用）
    const adminPane = rolePane(wrapper, 'ADMIN');
    const checkboxes = adminPane.findAll('input[type="checkbox"]');
    expect(checkboxes.length).toBeGreaterThan(0);
    for (const box of checkboxes) {
      expect((box.element as HTMLInputElement).disabled).toBe(true);
    }
    // ADMIN 页不渲染保存入口（只读区无覆写出网面）
    const saveButton = adminPane.findAll('button').find((b) => b.text() === '保存矩阵');
    expect(saveButton).toBeUndefined();
    wrapper.unmount();
  });

  it('ELEMENT 勾选态=角色 permCodes 与该组码集的交集（NURSE 含任务流转码）', async () => {
    const wrapper = mount(RolePermissionMatrixView);
    await flushPromises();

    await clickTab(wrapper, 'role-tabs', '护士');
    await clickTab(wrapper, 'type-tabs', 'ELEMENT');

    // 交集内码勾选（V1121 种子 NURSE 绑定 nursing:ward:btn:task）
    expect(
      (checkboxOf(wrapper, 'NURSE', 'nursing:ward:btn:task').element as HTMLInputElement).checked,
    ).toBe(true);
    // 交集外码未勾选（同域未绑定码与收费域码均空勾）
    expect(
      (checkboxOf(wrapper, 'NURSE', 'nursing:ward:btn:vital').element as HTMLInputElement).checked,
    ).toBe(false);
    expect(
      (checkboxOf(wrapper, 'NURSE', 'billing:refund:btn:approve').element as HTMLInputElement)
        .checked,
    ).toBe(false);
    wrapper.unmount();
  });

  it('勾选变更后保存以全量码集调用（含未变部分与清单外码带回）且提示需重新登录生效', async () => {
    vi.mocked(updateRolePermissions).mockResolvedValue(
      roleRow('NURSE', '护士', [
        'nursing:ward:view',
        'nursing:ward:btn:task',
        'billing:refund:btn:approve',
      ]),
    );
    const wrapper = mount(RolePermissionMatrixView);
    await flushPromises();

    await clickTab(wrapper, 'role-tabs', '护士');
    await clickTab(wrapper, 'type-tabs', 'ELEMENT');
    // 勾选新码 billing:refund:btn:approve（初始未勾；checkbox-group 以 emit 回填
    // v-model，TriageBoardView.spec 同款替身口径）——ELEMENT 组按特有码名定位
    const elementGroup = rolePane(wrapper, 'NURSE')
      .findAllComponents({ name: 'ElCheckboxGroup' })
      .find((group: VueWrapper) => group.text().includes('退费审批'));
    if (elementGroup === undefined) {
      throw new Error('未找到 NURSE pane 的 ELEMENT checkbox-group');
    }
    elementGroup.vm.$emit('update:modelValue', [
      'nursing:ward:btn:task',
      'billing:refund:btn:approve',
    ]);
    await flushPromises();
    await clickButton(wrapper, '保存矩阵');
    await flushPromises();

    // 全量覆写语义：载荷=原有绑定（含未变部分+清单外码）+新勾码，非单码增量
    expect(vi.mocked(updateRolePermissions)).toHaveBeenCalledTimes(1);
    const [calledCode, calledCodes] = vi.mocked(updateRolePermissions).mock.calls[0];
    expect(calledCode).toBe('NURSE');
    expect([...calledCodes].sort()).toEqual(
      [
        'nursing:ward:view',
        'nursing:ward:btn:task',
        'nursing:execution:btn:perform',
        'billing:refund:btn:approve',
      ].sort(),
    );
    // F4③ 提示文案逐字契约：矩阵重载 + 重新登录生效语义
    const successText = vi.mocked(ElMessage.success).mock.calls[0]?.[0] ?? '';
    expect(successText).toContain('重新登录生效');
    wrapper.unmount();
  });

  it('启停开关停用方向二次确认：取消不执行，确认后调 updateRoleStatus(NURSE, DISABLED)', async () => {
    vi.mocked(updateRoleStatus).mockResolvedValue(roleRow('NURSE', '护士', []));
    const wrapper = mount(RolePermissionMatrixView);
    await flushPromises();

    await clickTab(wrapper, 'role-tabs', '护士');
    const switchEl = rolePane(wrapper, 'NURSE').find('.role-bar .el-switch');
    expect(switchEl.exists()).toBe(true);

    // 取消确认：不出网，角色维持启用态（reject 载荷任意——EP confirm 取消即 reject）
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await switchEl.trigger('click');
    await flushPromises();
    expect(vi.mocked(updateRoleStatus)).not.toHaveBeenCalled();

    // 确认停用：以 NURSE + DISABLED 出网
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce({ action: 'confirm' } as MessageBoxData);
    await switchEl.trigger('click');
    await flushPromises();
    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(updateRoleStatus)).toHaveBeenCalledWith('NURSE', 'DISABLED');
    wrapper.unmount();
  });

  it('listPermissions 失败渲染空态兜底不崩（重试入口在位）', async () => {
    vi.mocked(listPermissions).mockRejectedValue(new Error('权限清单读取失败'));
    const wrapper = mount(RolePermissionMatrixView);
    await flushPromises();

    // 空态兜底：页面不白屏不抛错，渲染失败说明与重试入口
    expect(wrapper.text()).toContain('加载失败');
    expect(wrapper.findAll('button').some((b) => b.text() === '重新加载')).toBe(true);
    // 读端点失败后保存入口不出（无角色可编）
    expect(wrapper.findAll('button').some((b) => b.text() === '保存矩阵')).toBe(false);
    wrapper.unmount();
  });
});
