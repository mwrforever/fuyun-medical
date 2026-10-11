// 转抄工作台单测（/inpatient/transfer，M04 FU-M04-06 前端面 · 暖纸卷宗换脸重排 乙-4）：
// 门牌页首三锚（衬线标题/TRANSCRIPTION 英文小注/批注行「高危/输血类须双人核对 · 谁 · 时刻」）、
// 锚点①列表卡头「全选本页」与计数同位（PM 重排：统计与勾选动作同位；只勾当页不越页、
// 出网顺序=列表序）、锚点②高危行左缘印泥朱规线类（行级扫读双通道，与列内「高危」旗同义）、
// 锚点③红*显隐与 hasHighRisk 联动（保留契约：视觉必填指示，强制面归后端 IP-1016）、
// 锚点④提交成功盖钤印「已转抄 N 条」（成功反馈换约留痕：原 ElMessage.success
// 「批量核对完成：N 条已转抄」断言随勾选统计位置迁移一并按新契约重写——钤印代 message，
// G2 成功=钤印/失败=message 分工）、锚点⑤病区清单 listOrgs 真数据三态（G4 WARD_OPTIONS
// 假常量清零）、批量核对 IP-1016 4xx detail 原文透出（业务规则单点归后端）、出网携勾选
// 医嘱号集/转抄人/核对结论并刷新列表、诚实空态脸、PR-4F 权限两件。
// api mock 承载零出网（vi.mock('@/api/inpatient') 整模块替身 + @/api/system 替身），不打
// 真实网络；断言业务结果不绑定实现细节。重写留痕：原 mock 的 WARD_OPTIONS 假常量与未被本页
// 消费的 admissions/visits/beds/transfer/orders/discharge 死种子随本次重写清理（死代码纪律）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { transferWorklist } from '@/api/inpatient';
import type { TransferWorklistVO } from '@/api/inpatient';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import TransferWorklistView from './TransferWorklistView.vue';

vi.mock('@/api/inpatient', () => ({
  ORDER_TYPE_OPTIONS: [
    { code: 'DRUG', label: '药品' },
    { code: 'LAB', label: '检验' },
    { code: 'EXAM', label: '检查' },
    { code: 'SURGERY', label: '手术' },
    { code: 'BLOOD', label: '用血' },
    { code: 'NURSING', label: '护理' },
    { code: 'DIET', label: '膳食' },
    { code: 'CONSULT', label: '会诊' },
    { code: 'DISCHARGE_MED', label: '出院带药' },
  ],
  ORDER_CLASS_OPTIONS: [
    { code: 'LONG', label: '长期' },
    { code: 'STAT', label: '临时' },
  ],
  transferWorklist: { list: vi.fn(), check: vi.fn() },
}));

// 病区清单真数据源替身（G4：WARD_OPTIONS 假常量清零，门牌状态位病区下拉走 listOrgs WARD 档）
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

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖（存量 spec 同款空壳）
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

/** 待转抄行（AUDITED 医嘱聚合；highRisk 可覆写） */
function worklistMock(partial: Partial<TransferWorklistVO> = {}): TransferWorklistVO {
  return {
    orderNo: 'MO2026092500001',
    visitId: 'I2026092500001',
    patientId: '1932000000000000001',
    orderType: 'DRUG',
    orderClass: 'STAT',
    standbyFlag: false,
    freqCode: undefined,
    doctorId: 'D001',
    orderedAt: '2026-09-25T10:00:00+08:00',
    highRisk: false,
    ...partial,
  };
}

/** 空待转抄分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyWorklist() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按序种待转抄行集（顺序即出网契约序，锚点①载体） */
function worklistPage(rows: TransferWorklistVO[]) {
  return { content: rows, page: '0', size: '20', total: String(rows.length) };
}

/** 按 aria-label 勾选行复选框 */
async function checkRow(wrapper: VueWrapper, orderNo: string): Promise<void> {
  const checkbox = wrapper.find(`input[aria-label="勾选 ${orderNo}"]`);
  await checkbox.setValue(true);
}

/** 点击按钮（按可见文本定位；提交钮等动作出口） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  await button?.trigger('click');
}

describe('转抄工作台', () => {
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  /** 会话种子（auth store 从 sessionStorage 恢复）：含本页元素码——真实 NURSE 会话经登录
   * 契约导出含码，既有用例按钮保留、断言语义不变（D-21 申报规范，评审 D-I1 全局指令补齐） */
  function seedAuthSession(): void {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: { userId: 'u1', permissions: ['inpatient:transfer:btn:check'] },
      }),
    );
  }

  beforeEach(() => {
    for (const fn of [transferWorklist.list, transferWorklist.check, listOrgs]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链（病区清单默认单病区、列表默认空页）
    vi.mocked(listOrgs).mockResolvedValue(WARD_SEED);
    vi.mocked(transferWorklist.list).mockResolvedValue(emptyWorklist());
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
  });

  it('门牌页首三锚与页脚批注齐备：衬线标题「转抄工作台」+ TRANSCRIPTION 小注 + 批注行「高危/输血类须双人核对 · 谁 · 时刻」+ 数据时点页脚', async () => {
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    // 门牌页首挂载与衬线标题（契约 ⑧.1 门牌三锚之一；h2 页内大标题随收编清零）
    expect(wrapper.find('.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-title').text()).toBe('转抄工作台');
    expect(wrapper.find('.fuy-page-en').text()).toBe('TRANSCRIPTION');
    // 批注行：原 hint 文案逐字保留 + 「谁 · 时刻」齐备（会话无 displayName 以 — 占位零伪数据）
    const note = wrapper.find('.fuy-page-note').text();
    expect(note).toContain('病区 AUDITED 医嘱聚合');
    expect(note).toContain('高危/输血类须双人核对');
    expect(note).toContain('转抄人');
    // 页脚批注（契约 ⑧.8）：数据时点语义（未成功加载以 — 占位）
    expect(wrapper.find('.fuy-page-foot').text()).toContain('数据时点');
    // 门牌状态位：已选 N 墨底徽标（勾选统计上行至门牌，蓝图乙-4.2①）
    expect(wrapper.find('.transfer-selected-badge').text()).toContain('已选');
    wrapper.unmount();
  });

  it('待转抄列表加载渲染并透出高危红色标识（病区维度）', async () => {
    vi.mocked(transferWorklist.list).mockResolvedValue(
      worklistPage([
        worklistMock({ orderNo: 'MO2026092500001', highRisk: true }),
        worklistMock({ orderNo: 'MO2026092500002', orderType: 'LAB', highRisk: false }),
      ]),
    );
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('MO2026092500001');
    expect(text).toContain('MO2026092500002');
    // 高危标识仅命中高危行（机器判据类）
    expect(wrapper.findAll('.transfer-highrisk-flag').length).toBe(1);
    // 出网携病区维度
    expect(transferWorklist.list).toHaveBeenCalledWith(expect.objectContaining({ wardId: 'W01' }));
  });

  it('锚点②高危行左缘规线类断言：高危行 tr 挂 transfer-highrisk-row 规线类（行级双通道），非高危行不挂', async () => {
    vi.mocked(transferWorklist.list).mockResolvedValue(
      worklistPage([
        worklistMock({ orderNo: 'MO2026092500001', highRisk: true }),
        worklistMock({ orderNo: 'MO2026092500002', highRisk: false }),
      ]),
    );
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    // 规线类仅高危行持有（行级新增视觉），且与列内「高危」旗同行同义（双通道同源）
    const highriskRows = wrapper.findAll('tr.transfer-highrisk-row');
    expect(highriskRows.length).toBe(1);
    expect(highriskRows[0]?.find('.transfer-highrisk-flag').exists()).toBe(true);
    wrapper.unmount();
  });

  it('锚点①列表卡头「全选本页」与计数同位：勾选动作与统计同在列表卡头，全选只勾当页、再点全清、零勾选提交零出网拦截', async () => {
    vi.mocked(transferWorklist.list).mockResolvedValue(
      worklistPage([
        worklistMock({ orderNo: 'MO2026092500001', highRisk: true }),
        worklistMock({ orderNo: 'MO2026092500002' }),
      ]),
    );
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    // 同位证据：全选勾选框与「已选 N · 共 M 条」计数同在列表卡头（PM 重排裁决——统计不再
    // 驻留右栏卡头，远离勾选动作发生地）
    const cardHead = wrapper.find('.transfer-list-card .fuy-card-head');
    expect(cardHead.find('input[aria-label="全选本页"]').exists()).toBe(true);
    expect(cardHead.find('.transfer-list-count').text()).toContain('已选');
    // 全选本页：当页两行全勾 + 行金洗底 + 门牌徽标计数联动
    await cardHead.find('input[aria-label="全选本页"]').setValue(true);
    const rowBoxes = wrapper.findAll('input[aria-label^="勾选"]');
    expect(rowBoxes.length).toBe(2);
    for (const box of rowBoxes) {
      expect((box.element as HTMLInputElement).checked).toBe(true);
    }
    expect(wrapper.findAll('tr.transfer-row-selected').length).toBe(2);
    expect(wrapper.find('.transfer-selected-badge').text()).toContain('2');
    // 再点全选：当页全清（勾选集恒⊆当页清单——清单重载即清空，取消全选=清空）
    await cardHead.find('input[aria-label="全选本页"]').setValue(false);
    for (const box of wrapper.findAll('input[aria-label^="勾选"]')) {
      expect((box.element as HTMLInputElement).checked).toBe(false);
    }
    expect(wrapper.findAll('tr.transfer-row-selected').length).toBe(0);
    expect(wrapper.find('.transfer-selected-badge').text()).toContain('0');
    // 零勾选提交：显式校验零出网拦截（结构必填第一关）
    await clickButton(wrapper, '提交批量核对');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请先勾选待转抄医嘱');
    expect(transferWorklist.check).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('锚点①出网顺序=列表序：「全选本页」后提交按列表序携全当页医嘱号（分页语义不越页）', async () => {
    // 三行乱序种入（非字典序/非插入反序），出网序必须逐字等于列表渲染序
    vi.mocked(transferWorklist.list).mockResolvedValue(
      worklistPage([
        worklistMock({ orderNo: 'MO2026092500003' }),
        worklistMock({ orderNo: 'MO2026092500001', highRisk: true }),
        worklistMock({ orderNo: 'MO2026092500002' }),
      ]),
    );
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    await wrapper.find('input[aria-label="全选本页"]').setValue(true);
    await wrapper.find('input[aria-label="转抄人工号"]').setValue('N001');
    await clickButton(wrapper, '提交批量核对');
    await flushPromises();
    expect(transferWorklist.check).toHaveBeenCalledWith({
      orderNos: ['MO2026092500003', 'MO2026092500001', 'MO2026092500002'],
      transferNurseId: 'N001',
      conclusion: 'PASSED',
      secondCheckerId: undefined,
    });
    wrapper.unmount();
  });

  it('锚点③红*显隐与 hasHighRisk 联动：未勾高危行不显红*，勾选高危行显红*且占位文案切换（保留契约）', async () => {
    vi.mocked(transferWorklist.list).mockResolvedValue(
      worklistPage([
        worklistMock({ orderNo: 'MO2026092500001', highRisk: true }),
        worklistMock({ orderNo: 'MO2026092500002' }),
      ]),
    );
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    // 未勾高危：红*不显影（红*仅高危勾选态承载视觉必填指示，规则归后端 IP-1016）
    expect(wrapper.find('.transfer-second-required').exists()).toBe(false);
    // 勾选高危行：红*显影 + 第二核对人占位切高危口径
    await checkRow(wrapper, 'MO2026092500001');
    expect(wrapper.find('.transfer-second-required').exists()).toBe(true);
    expect(wrapper.find('input[aria-label="第二核对人"]').attributes('placeholder')).toContain(
      '高危医嘱必填第二核对人',
    );
    // 取消勾选：红*随 hasHighRisk 回落隐没
    await wrapper.find('input[aria-label="勾选 MO2026092500001"]').setValue(false);
    expect(wrapper.find('.transfer-second-required').exists()).toBe(false);
    wrapper.unmount();
  });

  it('高危行缺第二核对人透出 IP-1016 4xx detail 原文（业务规则后端把守）', async () => {
    vi.mocked(transferWorklist.list).mockResolvedValue(
      worklistPage([worklistMock({ orderNo: 'MO2026092500001', highRisk: true })]),
    );
    vi.mocked(transferWorklist.check).mockRejectedValue({
      errorCode: 'IP-1016',
      detail: '高危或输血类医嘱转抄须第二核对人复核',
    });
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    // 勾选高危行 + 填转抄人，第二核对人留空提交（红*为视觉指示，强制面由后端 IP-1016 把守）
    await checkRow(wrapper, 'MO2026092500001');
    await wrapper.find('input[aria-label="转抄人工号"]').setValue('N001');
    await clickButton(wrapper, '提交批量核对');
    await flushPromises();
    expect(transferWorklist.check).toHaveBeenCalledTimes(1);
    expect(vi.mocked(ElMessage.error)).toHaveBeenCalledWith('高危或输血类医嘱转抄须第二核对人复核');
    // 失败不刷新列表（list 仍只 1 次）
    expect(transferWorklist.list).toHaveBeenCalledTimes(1);
  });

  it('批量提交出网携勾选医嘱号集/转抄人/核对结论并刷新列表，成功盖钤印「已转抄 2 条」（成功反馈换约留痕：钤印代 message）', async () => {
    vi.mocked(transferWorklist.list).mockResolvedValue(
      worklistPage([
        worklistMock({ orderNo: 'MO2026092500001', highRisk: true }),
        worklistMock({ orderNo: 'MO2026092500002', orderType: 'LAB' }),
      ]),
    );
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    await checkRow(wrapper, 'MO2026092500001');
    await checkRow(wrapper, 'MO2026092500002');
    await wrapper.find('input[aria-label="转抄人工号"]').setValue('N001');
    await wrapper.find('input[aria-label="第二核对人"]').setValue('N002');
    await clickButton(wrapper, '提交批量核对');
    await flushPromises();
    expect(transferWorklist.check).toHaveBeenCalledWith({
      orderNos: ['MO2026092500001', 'MO2026092500002'],
      transferNurseId: 'N001',
      conclusion: 'PASSED',
      secondCheckerId: 'N002',
    });
    // 锚点④钤印计数文案：动词结果 + 逐条数回显（G2 成功=钤印；失败永不盖印）。
    // 留痕：原断言 ElMessage.success「批量核对完成：2 条已转抄」按乙-4.4 新契约重写为钤印，
    // 并反向断言 message 成功通道零调用（双通道不混用）
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.classes()).toContain('is-show');
    expect(stamp.text()).toContain('已转抄 2 条');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 提交成功后列表重载（初载+刷新=2 次）
    expect(transferWorklist.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('锚点⑤病区真数据三态（装载）：listOrgs({type:"WARD"}) 在途禁用且列表零出网，到达默认落首项并携病区出网', async () => {
    let releaseWards: () => void = () => {};
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          releaseWards = () => resolve(WARD_SEED);
        }),
    );
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    // 页面装配即拉真数据病区清单（假常量清零后唯一真数据源，册级总则 G4）
    expect(vi.mocked(listOrgs)).toHaveBeenCalledWith({ type: 'WARD' });
    // 在途：病区下拉禁用（出网值必须锚定真实病区编码），列表零出网
    const wardSelect = wrapper.find('select[aria-label="病区选择"]');
    expect(wardSelect.attributes('disabled')).toBeDefined();
    expect(transferWorklist.list).not.toHaveBeenCalled();
    // 清单到达：下拉解禁、选项=「名称（编码）」、默认落首项并携病区出网拉列表
    releaseWards();
    await flushPromises();
    expect(wardSelect.attributes('disabled')).toBeUndefined();
    expect(wardSelect.findAll('option').map((option) => option.text())).toEqual([
      '演示病区（W01）',
    ]);
    expect(transferWorklist.list).toHaveBeenCalledWith(expect.objectContaining({ wardId: 'W01' }));
    wrapper.unmount();
  });

  it('锚点⑤病区真数据三态（失败恢复）：清单失败给「说问题+给恢复」，重新加载后列表携病区出网', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('网络不可用'));
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    // 失败态=说问题给恢复（G4/G6：不伪装空选项），病区下拉禁用、列表零出网
    expect(wrapper.text()).toContain('病区清单加载失败');
    expect(wrapper.find('select[aria-label="病区选择"]').attributes('disabled')).toBeDefined();
    expect(transferWorklist.list).not.toHaveBeenCalled();
    // 重试恢复：清单到达后默认落首项并携病区出网（beforeEach 默认替身第二次调用回 WARD_SEED）
    await clickButton(wrapper, '重新加载');
    await flushPromises();
    expect(wrapper.text()).not.toContain('病区清单加载失败');
    expect(transferWorklist.list).toHaveBeenCalledWith(expect.objectContaining({ wardId: 'W01' }));
    wrapper.unmount();
  });

  it('锚点⑤病区真数据三态（空清单）：空清单给空态脸且列表零出网', async () => {
    vi.mocked(listOrgs).mockResolvedValue([]);
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    // 空清单=诚实空态脸（G6：暂无〈业务客体〉+ 下一步），病区下拉禁用、列表零出网
    expect(wrapper.find('.fuy-empty-title').text()).toContain('暂无启用病区');
    expect(wrapper.find('select[aria-label="病区选择"]').attributes('disabled')).toBeDefined();
    expect(transferWorklist.list).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('空列表给诚实空态脸（G6：暂无待转抄医嘱+下一步指引，非「暂无数据」裸词）', async () => {
    const wrapper = mount(TransferWorklistView);
    await flushPromises();
    expect(wrapper.find('.fuy-empty-title').text()).toContain('暂无待转抄医嘱');
    expect(wrapper.find('.fuy-empty-hint').text()).not.toBe('');
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(TransferWorklistView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 inpatient:transfer:btn:check 时提交批量核对按钮不渲染（D-34 无码全隐藏）', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 1 } }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('批量核对');
    expect(wrapper.text()).not.toContain('提交批量核对');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 inpatient:transfer:btn:check 时提交批量核对按钮渲染', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['inpatient:transfer:btn:check'] },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.findAll('button').some((b) => b.text() === '提交批量核对')).toBe(true);
    wrapper.unmount();
  });
});
