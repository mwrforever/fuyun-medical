// 权限管理台单测（/system/permissions，暖纸卷宗换脸重排 戊-1 · 蓝图 spec 锚点 ①~⑤）：
// 「换脸不换业务」——listRoles/listPermissions/updateRolePermissions/updateRoleStatus 四函数
// 接线、ELEMENT 勾选态=permCodes 交集、保存全量覆写（含清单外码原样带回防静默丢弃）、
// ADMIN 只读/禁停、DISABLED 二次确认（踢出后果文案）语义零放宽；断言现代化留痕
// （本次改动直接波及项，按新契约重写非放宽）：
// ① 裸页收编：根挂 .fuy-page（全站唯一未挂页补挂的机器证据），页根禁挂 .fuy-stagger（契约 ⑦.4）；
// ② 门牌页首三锚（衬线标题/英文小注/批注行=原 desc 升批注，原锚点逐字保留）+「已选 x/y」
//    墨底徽标（蓝图戊-1.2①）；
// ③ 双层签挂 .fuy-tabs 卷宗签语法 + DISABLED 角色签灰徽标（蓝图戊-1.2②）；ADMIN 只读注记
//    el-alert → 金洗底批注条（蓝图戊-1.4，结构反向断言）；失败兜底 el-empty → .fuy-empty
//    错误态变体「说问题+给恢复」（蓝图戊-1.3）；
// ④ 保存成功=钤印「已保存」+ F4③ message 文案逐字冻结（蓝图戊-1.4；原 ElMessage 单通道
//    断言按钤印+message 双通道重写）；
// ⑤ 清单外码带回与 ADMIN 禁停断言保留（既有测试语义不放宽）。
// api mock 承载零出网；sessionStorage 播种 admin 会话（批注行签认人读 auth store，页面
// 运行环境保真，DischargeManageView.spec 同款口径）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { MessageBoxData } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
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

/** 权限点分组清单（三类型各一小组：MENU 组含护士站菜单码、ELEMENT 组含护理与收费域码；
 * 全量 5 码=「已选 x/y」徽标分母） */
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
      // 其余两码分别落 MENU 与 ELEMENT 组（交集初始化断言面）
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

describe('权限管理台矩阵编辑器（暖纸卷宗 戊-1）', () => {
  /** 文件级 Pinia：门牌批注行签认人读取 auth 会话 store，每用例新实例防串扰 */
  let pinia: Pinia;

  /** 统一挂载（auth store 从 sessionStorage 恢复会话，须 pinia 在位） */
  function mountView(): VueWrapper {
    return mount(RolePermissionMatrixView, { global: { plugins: [pinia] } });
  }

  beforeEach(() => {
    vi.mocked(listRoles).mockReset();
    vi.mocked(listPermissions).mockReset();
    vi.mocked(updateRolePermissions).mockReset();
    vi.mocked(updateRoleStatus).mockReset();
    vi.mocked(ElMessage.success).mockReset();
    vi.mocked(ElMessageBox.confirm).mockReset();
    sessionStorage.clear();
    // 播种 admin 会话（页面运行环境保真：批注行签认人取会话显示名）
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
    pinia = createPinia();
    setActivePinia(pinia);
    // 默认装载 NURSE 场景（各用例按需覆写），防未 stub resolve 断链
    vi.mocked(listRoles).mockResolvedValue(nurseScenarioRoles());
    vi.mocked(listPermissions).mockResolvedValue(permissionGroups());
  });

  it('暖纸收编：根挂 .fuy-page 且页根禁挂 stagger，门牌页首三锚与「已选 x/y」墨底徽标在位（锚点①②）', async () => {
    const wrapper = mountView();
    await flushPromises();

    // 锚点①：全站唯一裸页补挂 .fuy-page（纵向单列由全局类承载），页根不挂级联（契约 ⑦.4）
    const root = wrapper.find('.fuy-page.perm-matrix');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    // 锚点②门牌三锚：衬线标题 + 英文小注 + 批注行（原 desc 升批注，原文案锚点逐字保留）
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('权限管理');
    expect(wrapper.find('.fuy-page-en').text()).toBe('PERMISSION MATRIX');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('签认人 系统管理员');
    expect(note.text()).toContain('勾选保存即全量覆写');
    expect(note.text()).toContain('各实例 403 矩阵实时重载，相关用户需重新登录生效');
    // 锚点②状态位：「已选 x/y」墨底徽标（ADMIN 激活无绑定 → 0 / 全量 5 码）
    const pill = wrapper.find('.fuy-page-head .perm-count-pill');
    expect(pill.exists()).toBe(true);
    expect(pill.text().replace(/\s+/g, ' ')).toContain('已选 0 / 5');
    wrapper.unmount();
  });

  it('双层签挂 .fuy-tabs 卷宗签语法；ADMIN 只读注记金洗底批注条替换 el-alert，矩阵区只读且保存入口不渲染（锚点③）', async () => {
    const wrapper = mountView();
    await flushPromises();

    // 锚点③：外层角色签与内层类型签均承卷宗签语法（card 型保留、色承全局 token 映射）
    expect(wrapper.find('.role-tabs').classes()).toContain('fuy-tabs');
    expect(wrapper.find('.type-tabs').classes()).toContain('fuy-tabs');
    // 锚点③反向断言：el-alert 结构清零，ADMIN 只读注记=金洗底批注条（语义文本逐字保留）
    expect(wrapper.find('.el-alert').exists()).toBe(false);
    const adminNote = rolePane(wrapper, 'ADMIN').find('.admin-note');
    expect(adminNote.exists()).toBe(true);
    expect(adminNote.text()).toContain('ADMIN 运行期全放');
    expect(adminNote.text()).toContain('矩阵不可编辑');
    // 既有语义保留：ADMIN pane 矩阵 checkbox 区只读（无绑定行可编辑；switch 同随禁用=禁停）
    const adminPane = rolePane(wrapper, 'ADMIN');
    const checkboxes = adminPane.findAll('input[type="checkbox"]');
    expect(checkboxes.length).toBeGreaterThan(0);
    for (const box of checkboxes) {
      expect((box.element as HTMLInputElement).disabled).toBe(true);
    }
    const adminSwitchInput = adminPane.find('.role-bar .el-switch input');
    expect((adminSwitchInput.element as HTMLInputElement).disabled).toBe(true);
    // 既有语义保留：ADMIN 页不渲染保存入口（只读区无覆写出网面）
    const saveButton = adminPane.findAll('button').find((b) => b.text() === '保存矩阵');
    expect(saveButton).toBeUndefined();
    wrapper.unmount();
  });

  it('ELEMENT 勾选态=角色 permCodes 与该组码集的交集（NURSE 含任务流转码；既有语义保留）', async () => {
    const wrapper = mountView();
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

  it('勾选变更保存全量覆写（含未变部分与清单外码带回），成功反馈=钤印「已保存」+ F4③ message 逐字冻结（锚点④⑤）', async () => {
    vi.mocked(updateRolePermissions).mockResolvedValue(
      roleRow('NURSE', '护士', [
        'nursing:ward:view',
        'nursing:ward:btn:task',
        'billing:refund:btn:approve',
      ]),
    );
    const wrapper = mountView();
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

    // 既有语义保留（锚点⑤）：全量覆写=原有绑定（含未变部分+清单外码）+新勾码，非单码增量
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
    // 锚点④成功反馈双通道：钤印「已保存」（动词结果，v-if 挂载制 aria-live 承读屏）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已保存');
    expect(stamp.attributes('aria-live')).toBe('polite');
    // 锚点④：F4③ message 批注条文案逐字冻结（矩阵重载 + 重新登录生效语义，钤印不承此长句）
    const successText = vi.mocked(ElMessage.success).mock.calls[0]?.[0] ?? '';
    expect(successText).toContain('各实例 403 矩阵已重载，相关用户需重新登录生效');
    wrapper.unmount();
  });

  it('启停开关停用方向二次确认：取消不执行，确认后调 updateRoleStatus(NURSE, DISABLED)；停用文案冻结（既有语义保留）', async () => {
    vi.mocked(updateRoleStatus).mockResolvedValue(roleRow('NURSE', '护士', []));
    const wrapper = mountView();
    await flushPromises();

    await clickTab(wrapper, 'role-tabs', '护士');
    const switchEl = rolePane(wrapper, 'NURSE').find('.role-bar .el-switch');
    expect(switchEl.exists()).toBe(true);

    // 取消确认：不出网，角色维持启用态（reject 载荷任意——EP confirm 取消即 reject）
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await switchEl.trigger('click');
    await flushPromises();
    expect(vi.mocked(updateRoleStatus)).not.toHaveBeenCalled();

    // 停用即踢出语义契约（评审 A-I1 文案修正）：确认弹窗必须明示在线会话立即登出后果
    // （入参为模板字符串，收窄断言避免对象默认字符串化告警）
    const confirmText = vi.mocked(ElMessageBox.confirm).mock.calls[0]?.[0] as string | undefined;
    expect(confirmText ?? '').toContain('在线会话立即登出');
    expect(confirmText ?? '').not.toContain('不回溯撤销');

    // 确认停用：以 NURSE + DISABLED 出网
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce({ action: 'confirm' } as MessageBoxData);
    await switchEl.trigger('click');
    await flushPromises();
    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(updateRoleStatus)).toHaveBeenCalledWith('NURSE', 'DISABLED');
    wrapper.unmount();
  });

  it('listPermissions 失败渲染 .fuy-empty 错误态变体（说问题给恢复+重新加载墨实底钮），零 el-empty 残留（锚点③失败半边）', async () => {
    vi.mocked(listPermissions).mockRejectedValue(new Error('权限清单读取失败'));
    const wrapper = mountView();
    await flushPromises();

    // 错误态不得伪装空态：.fuy-empty 承载「说问题+给恢复」，el-empty 结构清零（蓝图戊-1.3）
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.attributes('role')).toBe('alert');
    expect(empty.find('.fuy-empty-title').text()).toContain('加载失败');
    expect(empty.find('.fuy-empty-hint').text()).toContain('重新加载');
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    // 恢复入口：重新加载钮在位（墨实底=type primary，由 token 映射承载）
    expect(wrapper.findAll('button').some((b) => b.text() === '重新加载')).toBe(true);
    // 既有语义保留：读端点失败后保存入口不出（无角色可编）
    expect(wrapper.findAll('button').some((b) => b.text() === '保存矩阵')).toBe(false);
    wrapper.unmount();
  });

  it('DISABLED 角色签挂灰徽标（蓝图戊-1.2②：停用态灰 info 洗底，非状态朱）', async () => {
    vi.mocked(listRoles).mockResolvedValue([
      roleRow('ADMIN', '系统管理员', []),
      { ...roleRow('GUEST', '访客', []), status: 'DISABLED' },
      roleRow('NURSE', '护士', []),
    ]);
    const wrapper = mountView();
    await flushPromises();

    const guestTab = wrapper
      .findAll('.role-tabs .el-tabs__item')
      .find((item) => item.text().includes('访客'));
    if (guestTab === undefined) {
      throw new Error('未找到访客角色页签');
    }
    // 灰徽标=info 语义洗底胶囊（双通道：灰底+「停用」文字，不用状态朱承停用）
    const badge = guestTab.find('.fuy-status-pill--info');
    expect(badge.exists()).toBe(true);
    expect(badge.text()).toBe('停用');
    wrapper.unmount();
  });
});
