// 设备绑定页单测（/iot/bindings，M14 FU-M14-03 绑定生命周期前端面 · 暖纸卷宗换脸重排 丙-3）：
// 「换脸不换业务」——绑定/解绑状态机、解绑原因强制拦截、visit 号 14 位 I 格式显式校验、
// BOUND 行解绑入口显隐零变动；spec 按丙-3 蓝图锚点重写（蓝图丙-3.7 ①~⑤）。
// 断言现代化留痕（本次改动直接波及项，按新契约重写非放宽）：
// ① 五元组平铺九列 → 「归属」列组表头（el-table 嵌套列组，蓝图丙-3.2 PM 重排裁决）：新增
//    二级表头结构断言（归属父列 colspan=4 + 发丝分隔挂类 + 四子列精确序）——蓝图丙-3.7-①；
// ② 成功反馈 ElMessage.success → 钤印 .fuy-stamp-toast（绑定「已绑定」/解绑受理
//    「已受理解绑」，UNBINDING 异步语义文案区分——蓝图丙-3.4/锚点③），原 ElMessage.success
//    断言按新契约重写并补校验拦截零钤印反向断言（钤印只承成功确认）；
// ③ IOT_WARD_OPTIONS 假常量清零 → listOrgs({type:'WARD'}) 真数据三态（册级总则 G4/
//    蓝图丙-3.7-④）：替身键清理 + 装载在途禁用/到达默认落首项/失败说问题给恢复+出网拦截；
// ④ 「暂无绑定记录」el-empty → .fuy-empty 空态脸（契约 ⑥/蓝图丙-3.7-⑤）；
// ⑤ 门牌页首三锚 + 页面根节点禁挂 .fuy-stagger（契约 ⑦.4/蓝图丙-3.2）。
// api mock 承载零出网（vi.mock('@/api/iot') + vi.mock('@/api/system') 整模块替身），
// 断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { bindings } from '@/api/iot';
import type { BindingVO } from '@/api/iot';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import BindingManageView from './BindingManageView.vue';

vi.mock('@/api/iot', () => ({
  BINDING_STATUS_LABELS: {
    BOUND: '绑定中',
    UNBINDING: '解绑中',
    UNBOUND: '已解绑',
  },
  BIND_TYPE_LABELS: {
    FIXED: '固定式',
    MOBILE: '移动式',
  },
  bindings: { list: vi.fn(), bind: vi.fn(), unbind: vi.fn() },
}));

// 病区清单真数据源替身（G4：IOT_WARD_OPTIONS 假常量清零，绑定表单病区下拉走 listOrgs WARD 档）
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
}));

// 仅替身 ElMessage（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table（含嵌套列组）布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 病区清单种子（listOrgs WARD 档出参；label=「名称（编码）」渲染口径的载体） */
const WARD_SEED: OrgVO[] = [
  { id: '1', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD', sort: 1 },
];

/** 绑定行（五元组快照；状态可覆写） */
function bindingMock(partial: Partial<BindingVO> = {}): BindingVO {
  return {
    id: '901',
    deviceId: 'dev-001',
    patientId: '1932000000000000001',
    visitId: 'I2026092500001',
    bedId: '903',
    wardId: 'W01',
    bindType: 'FIXED',
    status: 'BOUND',
    bindReason: '术后监护',
    boundBy: 'N001',
    boundAt: '2026-09-25T10:00:00+08:00',
    ...partial,
  };
}

/** 空绑定分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyPage() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按文本定位表格行 */
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

describe('设备绑定页', () => {
  /** 文件级 Pinia：v-perm 指令与门牌批注行签认人读取 auth 会话 store，每用例新实例防串扰 */
  let pinia: Pinia;

  /** 统一挂载（门牌批注行签认人消费 auth store + v-perm 元素码判定，均需 pinia 在位；
   *  perm 指令显式注册=main.ts 全局注册仅应用装配态，单测自备） */
  function mountView(): VueWrapper {
    return mount(BindingManageView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #33）：真实 IOT_ADMIN 会话经登录契约导出含码含名，既有用例语义不变
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
          permissions: ['iot:binding:btn:manage'],
        },
      }),
    );
    for (const fn of [bindings.list, bindings.bind, bindings.unbind, listOrgs]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链；病区清单真数据源默认落种子（G4 真数据三态可复现）
    vi.mocked(bindings.list).mockResolvedValue(emptyPage());
    vi.mocked(listOrgs).mockResolvedValue(WARD_SEED);
  });

  it('门牌页首锚点：衬线标题「设备绑定」+ 英文小注 + 批注行 + 状态筛选/刷新 + 页面根不挂 stagger（契约 ⑦.4）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 门牌三锚（蓝图丙-3.2/契约 ⑧.1）：衬线标题 + 装饰英文小注 + 批注行（hint 升批注带「谁·时刻」）
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('设备绑定');
    expect(wrapper.find('.fuy-page-en').text()).toBe('BINDING');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('五元组快照权威源 · 绑定历史只增 · 解绑原因强制留痕');
    expect(note.text()).toContain('签认人 陆物联');
    // 状态位=状态筛选+刷新（蓝图丙-3.2）
    expect(wrapper.find('select[aria-label="绑定状态筛选"]').exists()).toBe(true);
    expect(wrapper.findAll('button').some((b) => b.text() === '刷新')).toBe(true);
    // 契约 ⑦.4：页面根节点禁挂 .fuy-stagger（flow 包裹 div 承接 flex+gap）
    const root = wrapper.find('.fuy-page.binding-manage');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(root.find('.fuy-stagger.binding-flow').exists()).toBe(true);
    wrapper.unmount();
  });

  it('锚点①列组表头结构：归属父列 colspan=4+发丝分隔挂类+四子列精确序，顶级列齐备', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [bindingMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    // 二级表头（蓝图丙-3.2/3.3）：首行=顶级列+归属列组父级，次行=归属四子列
    const headRows = wrapper.findAll('thead tr');
    expect(headRows.length).toBe(2);
    const firstRowThs = headRows[0].findAll('th').map((th) => th.text().trim());
    expect(firstRowThs).toContain('设备 ID');
    expect(firstRowThs).toContain('归属');
    expect(firstRowThs).toEqual(
      expect.arrayContaining(['模式', '状态', '解绑原因', '绑定时间', '操作']),
    );
    // 归属父列横跨四子列 + 发丝分隔挂类（scoped :deep 承载父级「归属」墨笔细线）
    const groupTh = headRows[0].findAll('th').find((th) => th.text().trim() === '归属');
    expect(groupTh).toBeDefined();
    expect(groupTh?.attributes('colspan')).toBe('4');
    expect(groupTh?.classes()).toContain('binding-group-head');
    // 四子列全量精确序（患者/就诊/床位/病区——五元组快照列组的新契约）
    const subThs = headRows[1].findAll('th').map((th) => th.text().trim());
    expect(subThs).toEqual(['患者号', '就诊号', '床位', '病区']);
    wrapper.unmount();
  });

  it('绑定列表加载渲染五元组列与状态三态徽标（状态类机器判据）', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [
        bindingMock({ id: '901', deviceId: 'dev-001', status: 'BOUND' }),
        bindingMock({ id: '902', deviceId: 'dev-002', status: 'UNBINDING' }),
        bindingMock({
          id: '903',
          deviceId: 'dev-003',
          status: 'UNBOUND',
          bindType: 'MOBILE',
          unbindReason: '转床',
        }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mountView();
    await flushPromises();
    const text = wrapper.text();
    // 五元组列可见（设备/患者/就诊/床位/病区）
    expect(text).toContain('dev-001');
    expect(text).toContain('1932000000000000001');
    expect(text).toContain('I2026092500001');
    expect(text).toContain('903');
    expect(text).toContain('W01');
    expect(text).toContain('固定式');
    expect(text).toContain('移动式');
    // 解绑原因留痕可见（历史只增口径）
    expect(text).toContain('转床');
    // 三态色标状态类契约（机器判据；色值经语义 token 承载）
    expect(wrapper.find('.fuy-binding-tag--bound').exists()).toBe(true);
    expect(wrapper.find('.fuy-binding-tag--unbinding').exists()).toBe(true);
    expect(wrapper.find('.fuy-binding-tag--unbound').exists()).toBe(true);
    wrapper.unmount();
  });

  it('锚点④病区清单真数据三态（装载）：listOrgs({type:"WARD"}) 装配即拉，在途禁用，到达默认落首项', async () => {
    let releaseWards: () => void = () => {};
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          releaseWards = () => resolve(WARD_SEED);
        }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 页面装配即拉真数据病区清单（IOT_WARD_OPTIONS 假常量清零后唯一真数据源，册级总则 G4）
    expect(vi.mocked(listOrgs)).toHaveBeenCalledWith({ type: 'WARD' });
    // 在途：绑定表单病区下拉禁用（出网值必须锚定真实病区编码）
    const wardSelect = wrapper.find('select[aria-label="病区"]');
    expect(wardSelect.attributes('disabled')).toBeDefined();
    // 清单到达：下拉解禁、选项=「名称（编码）」渲染口径、默认落首项
    releaseWards();
    await flushPromises();
    expect(wardSelect.attributes('disabled')).toBeUndefined();
    expect(wardSelect.findAll('option').map((option) => option.text())).toEqual([
      '演示病区（W01）',
    ]);
    expect((wardSelect.element as HTMLSelectElement).value).toBe('W01');
    wrapper.unmount();
  });

  it('锚点④病区清单真数据三态（失败恢复）：说问题给恢复+失败态出网拦截，重试后默认落首项', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('网络不可用'));
    const wrapper = mountView();
    await flushPromises();
    // 失败态=说问题给恢复（G4/G6：不伪装空选项），病区下拉禁用
    expect(wrapper.text()).toContain('病区清单加载失败');
    const wardSelect = wrapper.find('select[aria-label="病区"]');
    expect(wardSelect.attributes('disabled')).toBeDefined();
    // 失败态出网拦截：病区编码空串零出网（出网值必须锚定真实病区编码，G4 三态失败态语义）
    await wrapper.find('input[aria-label="设备 ID"]').setValue('dev-009');
    await wrapper.find('input[aria-label="患者号"]').setValue('1932000000000000009');
    await wrapper.find('input[aria-label="就诊号"]').setValue('I2026092600009');
    await clickButton(wrapper, '确认绑定');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('病区清单未就绪，暂无法绑定');
    expect(bindings.bind).not.toHaveBeenCalled();
    // 重试恢复：清单到达后下拉解禁、默认落首项
    await clickButton(wrapper, '重试');
    await flushPromises();
    expect(wrapper.text()).not.toContain('病区清单加载失败');
    expect((wardSelect.element as HTMLSelectElement).value).toBe('W01');
    wrapper.unmount();
  });

  it('绑定表单 visit_id 非 14 位 I 格式零出网显式校验拦截（文案冻结）', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('input[aria-label="设备 ID"]').setValue('dev-009');
    await wrapper.find('input[aria-label="患者号"]').setValue('1932000000000000009');
    // 14 位校验：缺 I 前缀且位数不足
    await wrapper.find('input[aria-label="就诊号"]').setValue('I123');
    await clickButton(wrapper, '确认绑定');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '就诊号应以 I 开头共 14 位（I+日期+流水）',
    );
    expect(bindings.bind).not.toHaveBeenCalled();
    // 校验拦截=业务拒绝通道：零钤印（钤印只承成功确认，蓝图丙-3.4）
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    wrapper.unmount();
  });

  it('绑定提交出网携五元组并刷新绑定列表，成功反馈=钤印「已绑定」（锚点③）', async () => {
    vi.mocked(bindings.bind).mockResolvedValue(bindingMock({ deviceId: 'dev-009' }));
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('input[aria-label="设备 ID"]').setValue('dev-009');
    await wrapper.find('input[aria-label="患者号"]').setValue('1932000000000000009');
    await wrapper.find('input[aria-label="就诊号"]').setValue('I2026092600009');
    await wrapper.find('input[aria-label="床位号"]').setValue('905');
    await wrapper.find('select[aria-label="绑定模式"]').setValue('MOBILE');
    await clickButton(wrapper, '确认绑定');
    await flushPromises();
    expect(bindings.bind).toHaveBeenCalledWith({
      deviceId: 'dev-009',
      patientId: '1932000000000000009',
      visitId: 'I2026092600009',
      bedId: '905',
      wardId: 'W01',
      bindType: 'MOBILE',
      bindReason: undefined,
    });
    // 成功反馈=钤印（蓝图丙-3.4，留痕见文件头②）：文案=动词结果「已绑定」，不再走 ElMessage
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已绑定');
    expect(stamp.attributes('aria-live')).toBe('polite');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(bindings.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('锚点②解绑原因强制：空原因零出网拦截（文案冻结）', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [bindingMock({ id: '901', deviceId: 'dev-001', status: 'BOUND' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    await clickRowButton(wrapper, 'dev-001', '解绑');
    await flushPromises();
    // 不填原因直接确认 → 零出网拦截
    await clickButton(wrapper, '确认解绑');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写解绑原因');
    expect(bindings.unbind).not.toHaveBeenCalled();
    // 拦截=业务拒绝通道：零钤印（钤印只承成功确认）
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    wrapper.unmount();
  });

  it('解绑携原因出网并刷新绑定列表，成功反馈=钤印「已受理解绑」（UNBINDING 异步语义文案区分，锚点③）', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [bindingMock({ id: '901', deviceId: 'dev-001', status: 'BOUND' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    await clickRowButton(wrapper, 'dev-001', '解绑');
    await flushPromises();
    await wrapper.find('textarea[aria-label="解绑原因"]').setValue('转床更换床位');
    await clickButton(wrapper, '确认解绑');
    await flushPromises();
    expect(bindings.unbind).toHaveBeenCalledWith('dev-001', { reason: '转床更换床位' });
    // 成功反馈=钤印（蓝图丙-3.4，留痕见文件头②）：「已受理解绑」=解绑受理异步语义（非终态）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已受理解绑');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(bindings.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('锚点⑤「暂无绑定记录」空态脸：主句=暂无〈业务客体〉+ 说明给下一步（零默认插画）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 空态脸（契约 ⑥/蓝图丙-3.7-⑤）：主句=暂无〈业务客体〉+ 说明给下一步
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无绑定记录');
    expect(empty.find('.fuy-empty-hint').text()).toContain('绑定');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话无 iot:binding:btn:manage 时绑定/解绑出网口全隐藏（D-34）', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [bindingMock({ deviceId: 'dev-001', status: 'BOUND' })],
      page: '0',
      size: '20',
      total: '1',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u5' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 绑定表单提交口无码隐藏（右栏表单可填不可出网）
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('确认绑定');
    // 解绑入口（盘点口径不挂码）可开窗读上下文，解绑出网口无码隐藏
    await clickRowButton(wrapper, 'dev-001', '解绑');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('确认解绑');
    // 列表读面不受元素码影响（绑定行仍渲染）
    expect(wrapper.text()).toContain('dev-001');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 iot:binding:btn:manage 时确认绑定与确认解绑可见', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [bindingMock({ deviceId: 'dev-001', status: 'BOUND' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('确认绑定');
    await clickRowButton(wrapper, 'dev-001', '解绑');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('确认解绑');
    wrapper.unmount();
  });
});
