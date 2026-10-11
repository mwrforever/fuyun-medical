// 命令中心页单测（/iot/commands，M16 命令下发 challenge 两步安全面前端面 · 暖纸卷宗换脸重排
// 丙-5）：「换脸不换业务」——两步禁用时序、challenge 生命周期、参数 JSON 显式校验文案、
// PR-4F 权限态断言全数保留；spec 按丙-5 蓝图锚点重写（蓝图丙-5.7 ①~⑤）。
// 断言现代化留痕（本次改动直接波及项，按新契约重写非放宽）：
// ① 门牌页首三锚 + 跨页白名单文案锚点（与丙-1「产品与物模型」命令登记互引，蓝图丙-5.7-④）
//    + 页面根节点禁挂 .fuy-stagger（契约 ⑦.4）；
// ② 挑战回显 p → 「挑战凭据」纸条（.command-challenge-credential 挂类 + challengeId
//    .fuy-num 回显 + 有效期，蓝图丙-5.2 PM 裁决；.fuy-content-fade 显影走 Transition 承载，
//    jsdom 真实时钟下过渡类时序 flaky，不做机器断言——母契约 ⑪.9 降级注记同口径）；
// ③ 成功反馈 ElMessage.success → 钤印「已下发」（蓝图丙-5.4/锚点③），失败=message 分叉
//    （业务拒绝通道零钤印反向断言，钤印永不承失败）；
// ④ 「暂无命令日志」el-empty → .fuy-empty 空态脸（契约 ⑥/蓝图丙-5.7-⑤，零默认插画）；
// ⑤ 两步禁用时序断言保留（挑战 null → 第二步 disabled，蓝图丙-5.7-①）。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { commands, devices } from '@/api/iot';
import type { CommandLogVO, DeviceVO } from '@/api/iot';
import { permDirective } from '@/directives/perm';
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
  /** 文件级 Pinia：v-perm 指令与门牌批注行签认人读取 auth 会话 store，每用例新实例防串扰 */
  let pinia: Pinia;

  /** 统一挂载（门牌批注行签认人消费 auth store + v-perm 元素码判定，均需 pinia 在位；
   *  perm 指令显式注册=main.ts 全局注册仅应用装配态，单测自备） */
  function mountView(): VueWrapper {
    return mount(CommandCenterView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #34）：真实 IOT_ADMIN 会话经登录契约导出含码含名，既有用例语义不变
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
          permissions: ['iot:command:btn:issue'],
        },
      }),
    );
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

  it('门牌页首锚点：衬线标题「命令中心」+ 英文小注 + 批注行跨页白名单文案 + 刷新 + 页面根不挂 stagger（契约 ⑦.4）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 门牌三锚（蓝图丙-5.2/契约 ⑧.1）：衬线标题 + 装饰英文小注 + 批注行（hint 升批注带「谁·时刻」）
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('命令中心');
    expect(wrapper.find('.fuy-page-en').text()).toBe('COMMAND CENTER');
    const note = wrapper.find('.fuy-page-note');
    // 跨页白名单文案锚点（蓝图丙-5.7-④）：与丙-1 命令登记互引的维护入口指引升批注保留
    expect(note.text()).toContain('challenge 两步安全门');
    expect(note.text()).toContain('治疗级命令须已登记产品命令白名单（维护入口：产品与物模型）');
    expect(note.text()).toContain('白名单校验由后端两步把守');
    expect(note.text()).toContain('签认人 陆物联');
    // 状态位=刷新（蓝图丙-5.2）
    expect(wrapper.findAll('button').some((b) => b.text() === '刷新')).toBe(true);
    // 契约 ⑦.4：页面根节点禁挂 .fuy-stagger（flow 包裹 div 承接 flex+gap）
    const root = wrapper.find('.fuy-page.command-center');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(root.find('.fuy-stagger.command-flow').exists()).toBe(true);
    wrapper.unmount();
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
    const wrapper = mountView();
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
    // 安全级白名单标注：治疗级徽标独立状态类（FU-M14-09 白名单口径，琥珀高亮承新值）
    expect(wrapper.find('.fuy-safety-tag--safety').exists()).toBe(true);
    expect(wrapper.find('.fuy-safety-tag--treatment').exists()).toBe(true);
    wrapper.unmount();
  });

  it('下发台必填与参数 JSON 显式校验零出网拦截（空设备/空命令/非法 JSON）', async () => {
    const wrapper = mountView();
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
    // 校验拦截=业务拒绝通道：零钤印（钤印只承成功确认，蓝图丙-5.4）
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    wrapper.unmount();
  });

  it('challenge 第一步合法出网携设备/命令/参数，凭据条显影回显挑战标识（锚点②）', async () => {
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
    const wrapper = mountView();
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
    // 挑战凭据条（蓝图丙-5.2 签批脸/锚点②）：挂类 + challengeId .fuy-num 回显 + 有效期
    const credential = wrapper.find('.command-challenge-credential');
    expect(credential.exists()).toBe(true);
    expect(credential.text()).toContain('挑战凭据');
    expect(credential.text()).toContain('有效期 120s');
    expect(credential.find('.fuy-num').text()).toBe('ch-901');
    // 待确认态：第二步按钮出现且解禁（凭据条下方墨实底主操作）
    const secondStep = wrapper.findAll('button').find((b) => b.text() === '第二步：确认下发');
    expect(secondStep).toBeDefined();
    expect(secondStep?.attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });

  it('未取得 challengeId 时第二步按钮禁用（两步门禁时序强制，零出网）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 挑战 null：凭据条不出现，第二步按钮禁用（蓝图丙-5.7-① 时序断言）
    expect(wrapper.find('.command-challenge-credential').exists()).toBe(false);
    const secondStep = wrapper.findAll('button').find((b) => b.text() === '第二步：确认下发');
    expect(secondStep).toBeDefined();
    expect(secondStep?.attributes('disabled')).toBeDefined();
    await secondStep?.trigger('click');
    await flushPromises();
    expect(commands.issue).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('challenge 第二步携 challengeId 出网下发，成功反馈=钤印「已下发」并清挑战态刷新日志（锚点③）', async () => {
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
    const wrapper = mountView();
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
    // 成功反馈=钤印（蓝图丙-5.4，留痕见文件头③）：文案=动词结果「已下发」，不再走 ElMessage
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已下发');
    expect(stamp.attributes('aria-live')).toBe('polite');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 挑战态清空：第二步按钮回置禁用、凭据条收起、challengeId 不再展示
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text() === '第二步：确认下发')
        ?.attributes('disabled'),
    ).toBeDefined();
    expect(wrapper.find('.command-challenge-credential').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('ch-901');
    // 命令日志重载（page 第二次调用）
    expect(commands.page).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('下发失败=message 分叉：业务拒绝走批注条且零钤印，挑战态保留（锚点③）', async () => {
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
    vi.mocked(commands.issue).mockRejectedValue({ detail: '命令不在产品命令白名单' });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('select[aria-label="下发设备"]').setValue('dev-001');
    await wrapper.find('input[aria-label="命令名"]').setValue('setVentilationParams');
    await clickButton(wrapper, '第一步：获取挑战');
    await flushPromises();
    await clickButton(wrapper, '第二步：确认下发');
    await flushPromises();
    // 失败=message 批注条（surfaceBizError 弹 detail 原文）；钤印永不承失败
    expect(vi.mocked(ElMessage.error)).toHaveBeenCalledWith('命令不在产品命令白名单');
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    // 失败不清挑战态：凭据条保留，可重试第二步或放弃本次挑战
    expect(wrapper.find('.command-challenge-credential').exists()).toBe(true);
    wrapper.unmount();
  });

  it('日志状态筛选切换出网携 status 重查', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('select[aria-label="命令状态筛选"]').setValue('FAILED');
    await flushPromises();
    expect(commands.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'FAILED', page: 0, size: 50 }),
    );
    wrapper.unmount();
  });

  it('锚点⑤「暂无命令日志」空态脸：主句=暂无〈业务客体〉+ 说明给下一步（零默认插画）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 空态脸（契约 ⑥/蓝图丙-5.7-⑤）：主句=暂无〈业务客体〉+ 说明给下一步
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无命令日志');
    expect(empty.find('.fuy-empty-hint').text()).toContain('下发');
    // el-empty 默认纸箱插画随空态脸收编清零（契约 ⑥）
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话无 iot:command:btn:issue 时两步下发按钮全隐藏（D-34）', async () => {
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u5' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // 获取挑战与确认下发两步出网口无码全隐藏（缺任一步即断链，无法完成下发）
    expect(buttonTexts).not.toContain('第一步：获取挑战');
    expect(buttonTexts).not.toContain('第二步：确认下发');
    // 下发台表单与日志读面不受元素码影响
    expect(wrapper.text()).toContain('命令下发台');
    expect(wrapper.find('select[aria-label="下发设备"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 iot:command:btn:issue 时两步下发按钮可见', async () => {
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('第一步：获取挑战');
    expect(buttonTexts).toContain('第二步：确认下发');
    wrapper.unmount();
  });
});
