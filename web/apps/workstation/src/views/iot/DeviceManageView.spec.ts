// 设备管理页单测（/iot/devices，M14 FU-M14-03/04 前端面 · 暖纸卷宗换脸重排 丙-2）：既有业务
// 断言全量保留（五态徽标状态类、注册必填缺项零出网校验、注册出网携字段并刷新、凭证重置
// 一次性 secret 展示与关窗即清、影子 desired/reported 两区、停用确认出网刷新、PR-4F 权限
// 两态），新增换脸锚点：门牌页首（衬线标题/DEVICE FLEET/批注行/根不挂 .fuy-stagger）、设备
// 概览胶囊组四字段映射与三态（骨架/内容/错误恢复——取自 getIotDashboardSummary 未消费
// 只读端点，甲-3 统计带同构）、影子抽屉 420→520 升档挂 .fuy-drawer、影子查询失败错误小卡
// （不伪装空态）、DISABLED 行停用钮隐藏、注册成功钤印「已注册」、注册卡 .fuy-form 挂类、
// 空态脸。api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），不打真实网络；断言业务
// 结果不绑定实现细节。
// 注：IOT_WARD_OPTIONS 本页零消费（丙-2 蓝图；常量清退归域收口 agent），mock 不再替身。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { devices, getIotDashboardSummary } from '@/api/iot';
import type { DashboardSummaryVO, DeviceVO } from '@/api/iot';
import { permDirective } from '@/directives/perm';
import DeviceManageView from './DeviceManageView.vue';

vi.mock('@/api/iot', () => ({
  DEVICE_STATUS_LABELS: {
    INACTIVE: '未激活',
    ONLINE: '在线',
    OFFLINE: '离线',
    ABNORMAL: '异常',
    DISABLED: '已停用',
  },
  devices: {
    list: vi.fn(),
    register: vi.fn(),
    detail: vi.fn(),
    shadow: vi.fn(),
    disable: vi.fn(),
    resetCredential: vi.fn(),
  },
  getIotDashboardSummary: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox（提示与确认断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
    },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 概览汇总种子（后端 DashboardSummaryVO 生成物形态：四计数 string 承载，字段名以生成物实读为准） */
function summaryMock(partial: Partial<DashboardSummaryVO> = {}): DashboardSummaryVO {
  return {
    deviceTotal: '12',
    onlineCount: '8',
    offlineCount: '3',
    activeAlarmCount: '1',
    ...partial,
  };
}

/** 设备行（五态可覆写） */
function deviceMock(partial: Partial<DeviceVO> = {}): DeviceVO {
  return {
    deviceId: 'dev-001',
    nodeId: undefined,
    productId: 'prod-monitor-001',
    deviceName: '3 床监护仪',
    deviceType: 'Monitor',
    accessMode: 'A',
    wardId: 'W01',
    status: 'ONLINE',
    lastOnlineAt: '2026-09-25T10:00:00+08:00',
    ...partial,
  };
}

/** 五态齐全的设备清单（床号 01-05 各一态） */
function fiveStateDevices(): DeviceVO[] {
  return [
    deviceMock({ deviceId: 'dev-1', deviceName: '监护仪甲', status: 'INACTIVE' }),
    deviceMock({ deviceId: 'dev-2', deviceName: '监护仪乙', status: 'ONLINE' }),
    deviceMock({ deviceId: 'dev-3', deviceName: '输液泵丙', status: 'OFFLINE' }),
    deviceMock({ deviceId: 'dev-4', deviceName: '输液泵丁', status: 'ABNORMAL' }),
    deviceMock({ deviceId: 'dev-5', deviceName: '呼吸机戊', status: 'DISABLED' }),
  ];
}

/** 空设备分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyPage() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按文本定位表格行（行内按钮操作载体） */
function findRow(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('tr').find((row) => row.text().includes(text));
}

/** 在指定行内按按钮文案点击（行作用域操作，防跨行误中） */
async function clickRowButton(
  wrapper: VueWrapper,
  rowText: string,
  buttonText: string,
): Promise<void> {
  const row = findRow(wrapper, rowText);
  const button = row?.findAll('button').find((b) => b.text() === buttonText);
  if (!button) {
    throw new Error(`未找到行内按钮：${buttonText}`);
  }
  await button.trigger('click');
}

/** 按按钮文案点击 */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('设备管理页', () => {
  /** 文件级 Pinia：门牌批注行签认人读 auth 会话 store + v-perm 指令元素权限判定，每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #35）：真实 IOT_ADMIN 会话经登录契约导出含码，既有用例语义不变
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u5',
          loginName: 'iotadmindemo',
          displayName: '陆物联',
          orgId: null,
          roles: ['iot_admin'],
          permissions: ['iot:device:btn:manage'],
        },
      }),
    );
    for (const fn of [
      devices.list,
      devices.register,
      devices.detail,
      devices.shadow,
      devices.disable,
      devices.resetCredential,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(getIotDashboardSummary).mockReset().mockResolvedValue(summaryMock());
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(devices.list).mockResolvedValue(emptyPage());
  });

  /** 统一挂载：pinia（门牌签认人）+ v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(DeviceManageView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('门牌页首：衬线标题「设备管理」+ 英文小注 DEVICE FLEET + 批注行（一机一密/IoTDA/签认人·时刻）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 页级骨架（契约 ⑧.1/G1）：根挂 .fuy-page 且不挂 .fuy-stagger（契约 ⑦.4 防双重进场节奏），
    // 级联由 flow 层承接（flex+gap 同构承接根级纵排）
    const root = wrapper.find('.fuy-page');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(wrapper.find('.device-flow.fuy-stagger').exists()).toBe(true);
    // 门牌三锚：衬线标题 + 装饰英文小注 + 批注行（原 hint 升批注，「谁·时刻」齐备）
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('设备管理');
    expect(wrapper.find('.fuy-page-en').text()).toBe('DEVICE FLEET');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('一机一密凭证托管');
    expect(note.text()).toContain('状态机经 IoTDA 驱动');
    expect(note.text()).toContain('签认人 陆物联');
    // 状态位=刷新钮（概览与清单联动重取）
    expect(wrapper.findAll('button').some((b) => b.text() === '刷新')).toBe(true);
    wrapper.unmount();
  });

  it('概览胶囊内容态：四字段映射（deviceTotal→总设备/onlineCount→在线/offlineCount→离线/activeAlarmCount→活跃告警）+ 四色变体双通道', async () => {
    vi.mocked(getIotDashboardSummary).mockResolvedValue(summaryMock());
    const wrapper = mountView();
    await flushPromises();
    // 挂载即单次出网取概览（PM 重排裁决：设备管理员开场先看健康面）
    expect(getIotDashboardSummary).toHaveBeenCalledTimes(1);
    const pills = wrapper.findAll('.device-overview-pills .fuy-status-pill');
    expect(pills).toHaveLength(4);
    // 四枚胶囊四色变体（中性=总数/success=在线/amber=离线/印泥朱=活跃告警）+ 双通道文字
    expect(pills[0].classes()).toContain('fuy-status-pill--info');
    expect(pills[0].text()).toBe('总设备 12');
    expect(pills[1].classes()).toContain('fuy-status-pill--success');
    expect(pills[1].text()).toBe('在线 8');
    expect(pills[2].classes()).toContain('fuy-status-pill--amber');
    expect(pills[2].text()).toBe('离线 3');
    expect(pills[3].classes()).toContain('fuy-status-pill--danger');
    expect(pills[3].text()).toBe('活跃告警 1');
    wrapper.unmount();
  });

  it('概览骨架态：汇总在途渲染静态金洗纸骨架占位（零伪数据数字）', async () => {
    vi.mocked(getIotDashboardSummary).mockImplementation(
      () => new Promise<DashboardSummaryVO>(() => undefined),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('.device-overview-skeleton .fuy-sk').exists()).toBe(true);
    expect(wrapper.find('.device-overview-pills').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('总设备');
    wrapper.unmount();
  });

  it('概览错误态：说问题给恢复（不伪装空态），重试成功回内容态', async () => {
    vi.mocked(getIotDashboardSummary).mockRejectedValueOnce(new Error('network down'));
    const wrapper = mountView();
    await flushPromises();
    const errorLine = wrapper.find('.device-overview-error');
    expect(errorLine.exists()).toBe(true);
    expect(errorLine.text()).toContain('设备概览加载失败');
    expect(errorLine.attributes('role')).toBe('alert');
    // 重试成功回内容态（说问题给恢复：失败可经重试钮重取）
    vi.mocked(getIotDashboardSummary).mockResolvedValue(summaryMock());
    const retry = errorLine.findAll('button').find((b) => b.text() === '重试');
    expect(retry).toBeDefined();
    await retry?.trigger('click');
    await flushPromises();
    expect(getIotDashboardSummary).toHaveBeenCalledTimes(2);
    expect(wrapper.find('.device-overview-pills').exists()).toBe(true);
    wrapper.unmount();
  });

  it('设备列表加载渲染并透出状态五态徽标（状态类机器判据）', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    const wrapper = mountView();
    await flushPromises();
    const text = wrapper.text();
    // 五态中文词表与设备名可见
    expect(text).toContain('监护仪甲');
    expect(text).toContain('呼吸机戊');
    expect(text).toContain('未激活');
    expect(text).toContain('在线');
    expect(text).toContain('离线');
    expect(text).toContain('异常');
    expect(text).toContain('已停用');
    // 五态色标状态类契约（机器判据；色值经 --fuy-color-device-* 语义 token 承载）
    expect(wrapper.find('.fuy-device-tag--inactive').exists()).toBe(true);
    expect(wrapper.find('.fuy-device-tag--online').exists()).toBe(true);
    expect(wrapper.find('.fuy-device-tag--offline').exists()).toBe(true);
    expect(wrapper.find('.fuy-device-tag--abnormal').exists()).toBe(true);
    expect(wrapper.find('.fuy-device-tag--disabled').exists()).toBe(true);
  });

  it('注册表单必填缺项零出网显式校验（设备ID/产品ID/名称/类型），表单挂 .fuy-form 表单脸', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 表单脸锚（契约 G2/⑤#1）：注册卡 el-form 挂 .fuy-form
    expect(wrapper.find('form.fuy-form').exists()).toBe(true);
    await clickButton(wrapper, '注册设备');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    expect(devices.register).not.toHaveBeenCalled();
  });

  it('注册提交出网携表单字段并刷新设备列表，成功盖钤印「已注册」（丙-2 锚点⑤）', async () => {
    vi.mocked(devices.register).mockResolvedValue(deviceMock({ deviceName: '6 床输液泵' }));
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('input[aria-label="设备 ID"]').setValue('dev-006');
    await wrapper.find('input[aria-label="产品 ID"]').setValue('prod-pump-001');
    await wrapper.find('input[aria-label="设备名称"]').setValue('6 床输液泵');
    await wrapper.find('input[aria-label="设备类型"]').setValue('InfusionPump');
    await clickButton(wrapper, '注册设备');
    await flushPromises();
    expect(devices.register).toHaveBeenCalledWith({
      deviceId: 'dev-006',
      nodeId: undefined,
      productId: 'prod-pump-001',
      deviceName: '6 床输液泵',
      deviceType: 'InfusionPump',
      accessMode: 'A',
    });
    // 成功反馈=钤印（契约 ⑧.5/G2：动词结果文案，v-if 挂载制 + is-show + aria-live）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已注册');
    expect(stamp.attributes('aria-live')).toBe('polite');
    // 成功反馈钤印制：注册不再走 message 通道（停用成功仍走 message——既有文案冻结）
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(devices.list).toHaveBeenCalledTimes(2);
  });

  it('凭证重置出网并一次性展示 secret（关窗即清不在页面残留），弹窗挂 .fuy-dialog + 印泥朱警示头', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    vi.mocked(devices.resetCredential).mockResolvedValue({
      deviceId: 'dev-2',
      credentialRef: 'cred-ref-002',
      secret: 'sec-one-time-999',
    });
    const wrapper = mountView();
    await flushPromises();
    // 行作用域点击「监护仪乙」（dev-2）的凭证重置，防跨行误中
    await clickRowButton(wrapper, '监护仪乙', '凭证重置');
    await flushPromises();
    expect(devices.resetCredential).toHaveBeenCalledWith('dev-2');
    // 弹层脸锚（丙-2 锚点②+G2）：挂 .fuy-dialog + 印泥朱警示头（一次性语义，文案冻结）
    expect(wrapper.find('.fuy-dialog').exists()).toBe(true);
    expect(wrapper.find('.device-secret-warn').text()).toContain('仅展示一次');
    // 一次性 secret 弹窗展示（凭证密文托管，secret 明文仅本次响应返回一次）
    expect(wrapper.text()).toContain('sec-one-time-999');
    // 关窗后 secret 不在页面残留（不落入列表与任何状态）
    await clickButton(wrapper, '我已保存');
    await flushPromises();
    expect(wrapper.text()).not.toContain('sec-one-time-999');
  });

  it('影子查询出网并在抽屉展示 desired/reported 两区', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    vi.mocked(devices.shadow).mockResolvedValue({
      desired: { alarmUpper: 150 },
      reported: { alarmUpper: 120 },
    });
    const wrapper = mountView();
    await flushPromises();
    // 行作用域点击「监护仪乙」（dev-2）的影子查询，防跨行误中
    await clickRowButton(wrapper, '监护仪乙', '影子');
    await flushPromises();
    expect(devices.shadow).toHaveBeenCalledWith('dev-2');
    // 抽屉展示影子两区内容（最后上报状态面）
    const text = wrapper.text();
    expect(text).toContain('期望值');
    expect(text).toContain('上报值');
    expect(text).toContain('alarmUpper');
  });

  it('影子抽屉 520px 升档挂 .fuy-drawer（丙-2 锚点③，420→520 统一档）', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    vi.mocked(devices.shadow).mockResolvedValue({ desired: {}, reported: {} });
    const wrapper = mountView();
    await flushPromises();
    await clickRowButton(wrapper, '监护仪乙', '影子');
    await flushPromises();
    // 弹层脸锚（契约 G2/⑤#10）：挂 .fuy-drawer 弹层脸 + 520px size（甲-2 同档统一）
    expect(wrapper.find('.fuy-drawer').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('size')).toBe('520px');
  });

  it('影子查询失败=抽屉内错误小卡说问题给恢复（不伪装空态），重试成功回两区', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    vi.mocked(devices.shadow).mockRejectedValue(new Error('network down'));
    const wrapper = mountView();
    await flushPromises();
    await clickRowButton(wrapper, '监护仪乙', '影子');
    await flushPromises();
    // 错误小卡（G6：说问题+给恢复；禁伪装成「（空）」空态）
    const errorCard = wrapper.find('.device-shadow-error');
    expect(errorCard.exists()).toBe(true);
    expect(errorCard.text()).toContain('影子查询失败');
    expect(wrapper.text()).not.toContain('（空）');
    // 重试成功回两区（空区以（空）占位——数据真值，非错误伪装）
    vi.mocked(devices.shadow).mockResolvedValue({ desired: {}, reported: {} });
    const retry = errorCard.findAll('button').find((b) => b.text() === '重新查询');
    expect(retry).toBeDefined();
    await retry?.trigger('click');
    await flushPromises();
    expect(devices.shadow).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('（空）');
    expect(wrapper.find('.device-shadow-error').exists()).toBe(false);
  });

  it('停用确认出网并刷新设备列表（DISABLED 置位由后端状态机承载）', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    const wrapper = mountView();
    await flushPromises();
    // 行作用域点击「监护仪乙」（dev-2）的停用，防跨行误中
    await clickRowButton(wrapper, '监护仪乙', '停用');
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalled();
    expect(devices.disable).toHaveBeenCalledWith('dev-2');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(devices.list).toHaveBeenCalledTimes(2);
  });

  it('DISABLED 行停用钮隐藏（丙-2 锚点④，终态行无停用入口），在线行停用入口保留', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    const wrapper = mountView();
    await flushPromises();
    // 终态行（呼吸机戊=DISABLED）：停用入口隐藏，只读影子入口保留
    const disabledRow = findRow(wrapper, '呼吸机戊');
    const disabledButtons = disabledRow?.findAll('button').map((b) => b.text()) ?? [];
    expect(disabledButtons).not.toContain('停用');
    expect(disabledButtons).toContain('影子');
    // 对照组：在线行（监护仪乙）停用入口保留
    const onlineRow = findRow(wrapper, '监护仪乙');
    expect(onlineRow?.findAll('button').map((b) => b.text())).toContain('停用');
  });

  it('空态脸：清单为空渲染「暂无设备」诚实空态（零默认纸箱插画，文案给下一步）', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('.fuy-empty').exists()).toBe(true);
    expect(wrapper.find('.fuy-empty-title').text()).toBe('暂无设备');
    expect(wrapper.find('.fuy-empty-hint').text()).toContain('注册');
  });

  it('PR-4F 权限态：会话无 iot:device:btn:manage 时注册/凭证重置/停用全隐藏（D-34）', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u5' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // 注册表单提交口与行内凭证重置/停用写口无码全隐藏
    expect(buttonTexts).not.toContain('注册设备');
    expect(buttonTexts).not.toContain('凭证重置');
    expect(buttonTexts).not.toContain('停用');
    // 只读「影子」查询抽屉入口不受元素码影响
    expect(buttonTexts).toContain('影子');
    // 设备列表读面不受元素码影响
    expect(wrapper.text()).toContain('监护仪乙');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 iot:device:btn:manage 时注册与行内写操作可见', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('注册设备');
    expect(buttonTexts).toContain('凭证重置');
    expect(buttonTexts).toContain('停用');
    wrapper.unmount();
  });
});
