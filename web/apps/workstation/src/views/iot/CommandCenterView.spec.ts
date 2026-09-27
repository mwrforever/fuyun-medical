// 命令中心页单测（/iot/commands，M16 命令下发 challenge 两步安全面前端面）：命令日志加载与
// 五态徽标（fuy-command-tag--{status} 机器判据）与安全级白名单标注（治疗级 fuy-safety-tag--
// treatment）、下发台必填/参数 JSON 显式校验零出网、challenge 第一步出网携设备/命令/参数并
// 进入待确认态、未取得 challengeId 直接下发零出网拦截、第二步携 challengeId 出网并清挑战态
// 刷新日志、日志状态筛选出网携参。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { commands, devices } from '@/api/iot';
import type { CommandLogVO, DeviceVO } from '@/api/iot';
import CommandCenterView from './CommandCenterView.vue';

vi.mock('@/api/iot', () => ({
  COMMAND_STATUS_LABELS: {
    ISSUED: '已下发',
    DELIVERED: '已送达',
    SUCCESS: '成功',
    FAILED: '失败',
    TIMEOUT: '超时',
  },
  SAFETY_LEVEL_LABELS: { SAFETY: '安全级', TREATMENT: '治疗级' },
  commands: { confirmChallenge: vi.fn(), issue: vi.fn(), page: vi.fn() },
  devices: { list: vi.fn() },
}));

// 仅替身 ElMessage（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
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

/** 命令日志行（可覆写状态/安全级） */
function commandLogMock(partial: Partial<CommandLogVO> = {}): CommandLogVO {
  return {
    id: '801',
    commandNo: 'CMD20260926001',
    deviceId: 'dev-001',
    commandName: 'setVentilationParams',
    params: { flow: 5 },
    safetyLevel: 'TREATMENT',
    operator: 'D001',
    deliverMode: 'SYNC',
    status: 'SUCCESS',
    issuedAt: '2026-09-26T10:00:00+08:00',
    resultAt: '2026-09-26T10:00:01+08:00',
    errorMsg: undefined,
    traceId: 'trace-1',
    createdAt: '2026-09-26T10:00:00+08:00',
    ...partial,
  };
}

/** 设备选项行 */
function deviceMock(partial: Partial<DeviceVO> = {}): DeviceVO {
  return {
    deviceId: 'dev-001',
    productId: 'prod-1',
    deviceName: '呼吸机-01',
    deviceType: 'VENTILATOR',
    accessMode: 'A',
    wardId: '1001',
    status: 'ONLINE',
    createdAt: '2026-09-25T10:00:00+08:00',
    ...partial,
  };
}

/** 空日志分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyPage() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按按钮文案点击 */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('命令中心页', () => {
  beforeEach(() => {
    for (const fn of [commands.confirmChallenge, commands.issue, commands.page, devices.list]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(commands.page).mockResolvedValue(emptyPage());
    vi.mocked(devices.list).mockResolvedValue({ content: [], page: '0', size: '50', total: '0' });
  });

  it('命令日志加载渲染五态状态徽标与安全级白名单标注（机器判据）', async () => {
    vi.mocked(commands.page).mockResolvedValue({
      content: [
        commandLogMock({ id: '801', status: 'ISSUED', safetyLevel: 'SAFETY' }),
        commandLogMock({ id: '802', status: 'SUCCESS', deviceId: 'dev-002' }),
        commandLogMock({ id: '803', status: 'FAILED', deviceId: 'dev-003', errorMsg: '设备离线' }),
        commandLogMock({ id: '804', status: 'TIMEOUT', deviceId: 'dev-004' }),
        commandLogMock({ id: '805', status: 'DELIVERED', deviceId: 'dev-005' }),
      ],
      page: '0',
      size: '20',
      total: '5',
    });
    const wrapper = mount(CommandCenterView);
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('CMD20260926001');
    expect(text).toContain('setVentilationParams');
    // 五态状态徽标状态类契约（机器判据；色值经语义 token 承载）
    expect(wrapper.find('.fuy-command-tag--issued').exists()).toBe(true);
    expect(wrapper.find('.fuy-command-tag--success').exists()).toBe(true);
    expect(wrapper.find('.fuy-command-tag--failed').exists()).toBe(true);
    expect(wrapper.find('.fuy-command-tag--timeout').exists()).toBe(true);
    expect(wrapper.find('.fuy-command-tag--delivered').exists()).toBe(true);
    // 安全级白名单标注：治疗级徽标独立状态类（FU-M14-09 白名单口径）
    expect(wrapper.find('.fuy-safety-tag--safety').exists()).toBe(true);
    expect(wrapper.find('.fuy-safety-tag--treatment').exists()).toBe(true);
  });

  it('下发台必填与参数 JSON 显式校验零出网拦截（空设备/空命令/非法 JSON）', async () => {
    const wrapper = mount(CommandCenterView);
    await flushPromises();
    // 空设备拦截
    await wrapper.find('input[aria-label="命令名"]').setValue('setVentilationParams');
    await clickButton(wrapper, '第一步：获取挑战');
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请选择设备');
    expect(commands.confirmChallenge).not.toHaveBeenCalled();
    // 空命令拦截
    await wrapper.find('select[aria-label="下发设备"]').setValue('dev-009');
    await wrapper.find('input[aria-label="命令名"]').setValue('');
    await clickButton(wrapper, '第一步：获取挑战');
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写命令名');
    expect(commands.confirmChallenge).not.toHaveBeenCalled();
    // 非法 JSON 拦截（禁裸 parse：显式 try/catch 校验后提示）
    await wrapper.find('input[aria-label="命令名"]').setValue('setVentilationParams');
    await wrapper.find('textarea[aria-label="命令参数 JSON"]').setValue('{flow:');
    await clickButton(wrapper, '第一步：获取挑战');
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '参数须为合法 JSON 对象（键值对形态），请修正后再试',
    );
    expect(commands.confirmChallenge).not.toHaveBeenCalled();
  });

  it('challenge 第一步合法出网携设备/命令/参数，返回后进入待确认态并展示挑战标识', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: [deviceMock()],
      page: '0',
      size: '50',
      total: '1',
    });
    vi.mocked(commands.confirmChallenge).mockResolvedValue({
      challengeId: 'ch-901',
      commandNo: 'CMD20260926002',
      expiresIn: '120',
    });
    const wrapper = mount(CommandCenterView);
    await flushPromises();
    await wrapper.find('select[aria-label="下发设备"]').setValue('dev-001');
    await wrapper.find('input[aria-label="命令名"]').setValue('setVentilationParams');
    await wrapper.find('textarea[aria-label="命令参数 JSON"]').setValue('{"flow":5}');
    await clickButton(wrapper, '第一步：获取挑战');
    await flushPromises();
    expect(commands.confirmChallenge).toHaveBeenCalledWith({
      deviceId: 'dev-001',
      commandName: 'setVentilationParams',
      params: { flow: 5 },
    });
    // 待确认态：challengeId 回显 + 第二步按钮出现
    expect(wrapper.text()).toContain('ch-901');
    expect(wrapper.findAll('button').some((b) => b.text() === '第二步：确认下发')).toBe(true);
  });

  it('未取得 challengeId 时第二步按钮禁用（两步门禁时序强制，零出网）', async () => {
    const wrapper = mount(CommandCenterView);
    await flushPromises();
    // 第二步按钮始终渲染但未取得挑战前置禁用：无法绕过第一步直接下发
    const secondStep = wrapper.findAll('button').find((b) => b.text() === '第二步：确认下发');
    expect(secondStep).toBeDefined();
    expect(secondStep?.attributes('disabled')).toBeDefined();
    await secondStep?.trigger('click');
    await flushPromises();
    expect(commands.issue).not.toHaveBeenCalled();
  });

  it('challenge 第二步携 challengeId 出网下发，成功后清挑战态并刷新命令日志', async () => {
    vi.mocked(devices.list).mockResolvedValue({
      content: [deviceMock()],
      page: '0',
      size: '50',
      total: '1',
    });
    vi.mocked(commands.confirmChallenge).mockResolvedValue({
      challengeId: 'ch-901',
      commandNo: 'CMD20260926002',
      expiresIn: '120',
    });
    vi.mocked(commands.issue).mockResolvedValue(commandLogMock({ status: 'ISSUED' }));
    const wrapper = mount(CommandCenterView);
    await flushPromises();
    await wrapper.find('select[aria-label="下发设备"]').setValue('dev-001');
    await wrapper.find('input[aria-label="命令名"]').setValue('setVentilationParams');
    await wrapper.find('textarea[aria-label="命令参数 JSON"]').setValue('{"flow":5}');
    await clickButton(wrapper, '第一步：获取挑战');
    await flushPromises();
    // 取得挑战后第二步按钮解除禁用
    const secondStep = wrapper.findAll('button').find((b) => b.text() === '第二步：确认下发');
    expect(secondStep?.attributes('disabled')).toBeUndefined();
    await secondStep?.trigger('click');
    await flushPromises();
    expect(commands.issue).toHaveBeenCalledWith({
      challengeId: 'ch-901',
      deviceId: 'dev-001',
      commandName: 'setVentilationParams',
      params: { flow: 5 },
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 挑战态清空：第二步按钮回置禁用、challengeId 不再展示
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text() === '第二步：确认下发')
        ?.attributes('disabled'),
    ).toBeDefined();
    expect(wrapper.text()).not.toContain('ch-901');
    // 命令日志重载（page 第二次调用）
    expect(commands.page).toHaveBeenCalledTimes(2);
  });

  it('日志状态筛选切换出网携 status 重查', async () => {
    const wrapper = mount(CommandCenterView);
    await flushPromises();
    await wrapper.find('select[aria-label="命令状态筛选"]').setValue('FAILED');
    await flushPromises();
    expect(commands.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'FAILED', page: 0, size: 50 }),
    );
  });
});
