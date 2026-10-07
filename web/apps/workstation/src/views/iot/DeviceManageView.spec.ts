// 设备管理页单测（/iot/devices，M14 FU-M14-03/04 前端面）：设备列表加载与状态五态徽标
// 渲染（fuy-device-tag--{status} 状态类契约机器判据）、注册表单必填缺项零出网显式校验、
// 注册提交出网携表单字段并刷新、凭证重置出网（secret 一次性弹窗展示、关窗即清不残留）、
// 影子查询抽屉出网（desired/reported 两区展示）、停用确认出网并刷新。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），不打真实网络；
// 断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { devices } from '@/api/iot';
import type { DeviceVO } from '@/api/iot';
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
  IOT_WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  devices: {
    list: vi.fn(),
    register: vi.fn(),
    detail: vi.fn(),
    shadow: vi.fn(),
    disable: vi.fn(),
    resetCredential: vi.fn(),
  },
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
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
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
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(devices.list).mockResolvedValue(emptyPage());
  });

  it('设备列表加载渲染并透出状态五态徽标（状态类机器判据）', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    const wrapper = mount(DeviceManageView);
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

  it('注册表单必填缺项零出网显式校验（设备ID/产品ID/名称/类型）', async () => {
    const wrapper = mount(DeviceManageView);
    await flushPromises();
    await clickButton(wrapper, '注册设备');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    expect(devices.register).not.toHaveBeenCalled();
  });

  it('注册提交出网携表单字段并刷新设备列表', async () => {
    vi.mocked(devices.register).mockResolvedValue(deviceMock({ deviceName: '6 床输液泵' }));
    const wrapper = mount(DeviceManageView);
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
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(devices.list).toHaveBeenCalledTimes(2);
  });

  it('凭证重置出网并一次性展示 secret（关窗即清不在页面残留）', async () => {
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
    const wrapper = mount(DeviceManageView);
    await flushPromises();
    // 行作用域点击「监护仪乙」（dev-2）的凭证重置，防跨行误中
    await clickRowButton(wrapper, '监护仪乙', '凭证重置');
    await flushPromises();
    expect(devices.resetCredential).toHaveBeenCalledWith('dev-2');
    // 一次性 secret 弹窗展示（凭证密文托管，secret 明文仅本次响应返回一次）
    expect(wrapper.text()).toContain('sec-one-time-999');
    expect(wrapper.text()).toContain('仅展示一次');
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
    const wrapper = mount(DeviceManageView);
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

  it('停用确认出网并刷新设备列表（DISABLED 置位由后端状态机承载）', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: fiveStateDevices(),
      page: '0',
      size: '20',
      total: '5',
    });
    const wrapper = mount(DeviceManageView);
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

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(DeviceManageView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

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
