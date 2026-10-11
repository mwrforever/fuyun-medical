// 护士工作站页单测（M05 前端面 · 暖纸卷宗换脸重排，逐页蓝图 甲-1）：既有业务断言全量保留
// （床位序渲染/体征显式校验/在途守卫/NS-1005 detail 透出/待复核确认移除/体温单符号类名契约/
// 评估高危容器类/任务逾期行与认领/常规生成/交接班双签/切换复位/EX-45 竞态/PR-4F 权限态），
// 另增暖纸锚点：门牌页首三锚（衬线标题/批注行/墨规收底挂类）、卷宗区序（卡墙→体温单→三列→
// 页脚，体温单升格的布局序机器证据）、卡墙选中金洗形态与 off-token #e5e7eb 清零（源码级）、
// 病区真数据三态（listOrgs type=WARD 在途骨架/失败恢复/空清单，假常量清零）、钤印「已录入」、
// 待复核行 fuy-flip 移除、卡墙 >120 卡床号段分组折叠。
// api mock 承载，不打真实网络；会话经 sessionStorage 种子恢复（当班护士=登录用户）。
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  assessments,
  assignments,
  chart,
  handovers,
  ioRecords,
  records,
  tasks,
  vitalSigns,
  wardPatients,
} from '@/api/nursing';
import type { VitalSignVO, WardPatientDetailVO, WardPatientVO } from '@/api/nursing';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import WardBoardView from './WardBoardView.vue';

vi.mock('@/api/nursing', () => ({
  /** 词表常量替身（与 api 层冻结导出同值；视图 select/词表渲染消费） */
  TEMP_SITE_SYMBOL: { AXILLARY: 'fuy-temp-x', ORAL: 'fuy-temp-dot', RECTAL: 'fuy-temp-circle' },
  TEMP_SITE_OPTIONS: [
    { code: 'AXILLARY', label: '腋下' },
    { code: 'ORAL', label: '口腔' },
    { code: 'RECTAL', label: '直肠' },
  ],
  NURSING_LEVEL_OPTIONS: [
    { code: 'SPECIAL', label: '特级护理' },
    { code: 'CRITICAL', label: '病重护理' },
    { code: 'NORMAL', label: '普通护理' },
  ],
  // 特殊事件词表替身含 SURGERY（对齐 api 层真实导出值域：换患者草稿复位用例需第二类型可切）
  SPECIAL_EVENT_OPTIONS: [
    { code: 'ADMISSION', label: '入院' },
    { code: 'SURGERY', label: '手术' },
  ],
  // WARD_OPTIONS 已随暖纸切片清零（病区清单走 listOrgs({type:'WARD'})，替身见 @/api/system mock）
  SHIFT_OPTIONS: [
    { code: 'DAY', label: '白班' },
    { code: 'EVENING', label: '小夜班' },
    { code: 'NIGHT', label: '大夜班' },
  ],
  // W-34：register/remove 已随端点退役，mock 面不再提供
  wardPatients: { list: vi.fn(), detail: vi.fn() },
  assignments: { list: vi.fn(), create: vi.fn(), remove: vi.fn() },
  vitalSigns: {
    record: vi.fn(),
    list: vi.fn(),
    pendingReview: vi.fn(),
    confirm: vi.fn(),
    reject: vi.fn(),
  },
  chart: { query: vi.fn(), addSpecialEvent: vi.fn() },
  ioRecords: { create: vi.fn(), list: vi.fn() },
  records: { create: vi.fn(), submit: vi.fn(), revise: vi.fn(), list: vi.fn() },
  assessments: { scales: vi.fn(), create: vi.fn(), list: vi.fn() },
  tasks: {
    list: vi.fn(),
    complete: vi.fn(),
    cancel: vi.fn(),
    claim: vi.fn(),
    generateRoutine: vi.fn(),
  },
  handovers: { generate: vi.fn(), complete: vi.fn(), list: vi.fn() },
  pda: { patientSummary: vi.fn(), patrol: vi.fn() },
}));

// 病区清单读端点替身（暖纸切片真数据面，WARD_OPTIONS 假常量消费清零后的唯一来源）
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
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
      prompt: vi.fn().mockResolvedValue({ value: '测试原因' }),
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

/** 病区种子（V1123__seed_sys_org 镜像：WARD 两病区 ACTIVE，sort 升序——W01 为演示主链路首项） */
const WARD_SEED: OrgVO[] = [
  { id: '1123000000000000001', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD', sort: 1 },
  { id: '1123000000000000002', orgCode: '1001', orgName: '演示病区', orgType: 'WARD', sort: 2 },
];

/** 会话种子（auth store 从 sessionStorage 恢复：当班护士=登录用户 u1/李护士；
 * permissions 含本视图四码 #25/#26/#27/#28——真实 NURSE 会话经登录契约导出含码，
 * 既有用例语义不变） */
function seedAuthSession(): void {
  sessionStorage.setItem(
    'fy:workstation:auth',
    JSON.stringify({
      token: 'test-token',
      refreshToken: 'test-refresh',
      user: {
        userId: 'u1',
        loginName: 'nursedemo',
        displayName: '李护士',
        orgId: null,
        roles: ['nurse'],
        permissions: [
          'nursing:ward:btn:vital',
          'nursing:ward:btn:record',
          'nursing:ward:btn:task',
          'nursing:ward:btn:handover',
        ],
      },
    }),
  );
}

/** 按指定码集覆写会话种子（PR-4F 权限态正反例：构造「含部分码/无码」会话形态） */
function seedAuthSessionWithPermissions(permissions: string[]): void {
  sessionStorage.setItem(
    'fy:workstation:auth',
    JSON.stringify({
      token: 'test-token',
      refreshToken: 'test-refresh',
      user: {
        userId: 'u1',
        loginName: 'nursedemo',
        displayName: '李护士',
        orgId: null,
        roles: ['nurse'],
        permissions,
      },
    }),
  );
}

/** 在区患者行（床位/visit 可覆写） */
function patientMock(partial: Partial<WardPatientVO> = {}): WardPatientVO {
  return {
    visitId: 'I20260923000000001',
    patientId: '1932000000000000001',
    wardId: 'W01',
    bedNo: '01',
    nursingLevel: 'NORMAL',
    admittedAt: '2026-09-20T08:00:00',
    ...partial,
  };
}

/** 患者详情（与 patientMock 同上下文；角标/在途任务可覆写；gender/age 恒 null 无值不渲染
 * ——W-34 换源口径，fixture 不再携带两字段） */
function detailMock(partial: Partial<WardPatientDetailVO> = {}): WardPatientDetailVO {
  return {
    wardId: 'W01',
    bedNo: '01',
    patientId: '1932000000000000001',
    visitId: 'I20260923000000001',
    patientName: '张三',
    nursingLevel: 'NORMAL',
    allergyFlag: false,
    riskFlags: '',
    admittedAt: '2026-09-20T08:00:00',
    allergies: [],
    assignments: [],
    inFlightTasks: [],
    ...partial,
  };
}

/** 当前月键（yyyy-MM，与视图默认月页一致——条目时点按当月构造防跨月漂移） */
function currentMonth(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

/** 当月某日 08:00 本地时点 ISO（体温单条目 entryTime 构造） */
function localIso(dayHour: [number, number]): string {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), dayHour[0], dayHour[1], 0, 0).toISOString();
}

/** 按按钮文案点击（el-button 与原生 button 通用） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/** 按按钮文案定位（在途/禁用断言用） */
function findButton(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

/** 选中首个床位卡（驱动全页患者上下文） */
async function selectFirstBed(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('.ward-bed-card').trigger('click');
  await flushPromises();
}

describe('护士工作站', () => {
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    for (const fn of [
      vitalSigns.record,
      vitalSigns.confirm,
      vitalSigns.reject,
      assessments.create,
      tasks.complete,
      tasks.cancel,
      tasks.claim,
      tasks.generateRoutine,
    ]) {
      vi.mocked(fn).mockReset();
    }
    for (const fn of [
      wardPatients.list,
      wardPatients.detail,
      assignments.list,
      assignments.create,
      assignments.remove,
      vitalSigns.list,
      vitalSigns.pendingReview,
      chart.query,
      chart.addSpecialEvent,
      ioRecords.create,
      ioRecords.list,
      records.create,
      records.submit,
      records.revise,
      records.list,
      assessments.scales,
      assessments.list,
      tasks.list,
      handovers.generate,
      handovers.complete,
      handovers.list,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 仅清调用计数：工厂内已 stub 的 resolve 实现保留（mockClear 不清除实现）
    vi.mocked(ElMessageBox.confirm).mockClear();
    seedAuthSession();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(wardPatients.list).mockResolvedValue([]);
    vi.mocked(wardPatients.detail).mockResolvedValue(detailMock());
    vi.mocked(assignments.list).mockResolvedValue([]);
    vi.mocked(vitalSigns.list).mockResolvedValue([]);
    vi.mocked(vitalSigns.pendingReview).mockResolvedValue([]);
    vi.mocked(chart.query).mockResolvedValue({
      visitId: 'I20260923000000001',
      chartMonth: currentMonth(),
      vitals: [],
      specialEvents: [],
      dailyValues: [],
    });
    vi.mocked(assessments.scales).mockResolvedValue([]);
    vi.mocked(assessments.list).mockResolvedValue([]);
    vi.mocked(tasks.list).mockResolvedValue([]);
    vi.mocked(handovers.list).mockResolvedValue([]);
    // 病区清单默认回种子（三态用例各自覆写为在途/失败/空清单）
    vi.mocked(listOrgs).mockReset();
    vi.mocked(listOrgs).mockResolvedValue(WARD_SEED);
  });

  it('加载病区一览按床位序渲染患者卡（03/01/02 → 01,02,03）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      patientMock({ bedNo: '03', visitId: 'I20260923000000003' }),
      patientMock({ bedNo: '01', visitId: 'I20260923000000001' }),
      patientMock({ bedNo: '02', visitId: 'I20260923000000002' }),
    ]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    const beds = wrapper.findAll('.ward-bed-no').map((node) => node.text());
    expect(beds).toEqual(['01', '02', '03']);
    wrapper.unmount();
  });

  it('任务区薄改：待执行任务可认领（assigneeId=当班护士留痕）且成功后重拉清单', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    vi.mocked(tasks.list).mockResolvedValue([
      {
        id: '9401',
        taskNo: 'T20260923002',
        patientId: '1932000000000000001',
        visitId: 'I20260923000000001',
        wardId: 'W01',
        bedNo: '01',
        taskType: 'PATROL',
        planTime: '2026-09-23 10:00:00',
        status: 'PENDING',
      },
    ]);
    vi.mocked(tasks.claim).mockResolvedValue({ taskNo: 'T20260923002', status: 'IN_PROGRESS' });
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '认领');
    await flushPromises();
    // 认领留痕锚点=会话用户 userId（u1），成功后清单重拉
    expect(vi.mocked(tasks.claim)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(tasks.claim)).toHaveBeenCalledWith('T20260923002', { assigneeId: 'u1' });
    expect(vi.mocked(tasks.list).mock.calls.length).toBeGreaterThanOrEqual(2);
    wrapper.unmount();
  });

  it('任务区薄改：生成常规任务确认后出网并回显生成条数', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    vi.mocked(tasks.generateRoutine).mockResolvedValue({ createdTasks: 6 });
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '生成常规任务');
    await flushPromises();
    expect(vi.mocked(tasks.generateRoutine)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(tasks.generateRoutine).mock.calls[0]?.[0]?.wardId).toBe('W01');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledWith('已生成 6 条常规任务');
    wrapper.unmount();
  });

  it('体征录入非整数触发显式校验并零出网（36.x / abc）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await selectFirstBed(wrapper);
    // 体温键入 36.x：change 触发显式校验（§3.7 @change + 提交双触发）
    const tempInput = wrapper.find('input[inputmode="decimal"]');
    await tempInput.setValue('36.x');
    await tempInput.trigger('change');
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '体温应为 35.0–42.0 的数值（如 36.5），请重新输入',
    );
    await clickButton(wrapper, '录入体征');
    await flushPromises();
    expect(vi.mocked(vitalSigns.record)).not.toHaveBeenCalled();
    // 脉搏键入 abc：文本承载显式校验拦截（禁裸 parse 口径）
    await tempInput.setValue('');
    const pulseInput = wrapper.findAll('input[inputmode="numeric"]')[0];
    await pulseInput.setValue('abc');
    await clickButton(wrapper, '录入体征');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('脉搏应为 20–250 的整数');
    expect(vi.mocked(vitalSigns.record)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('体征录入在途守卫拦截重复提交（双击零二次出网、按钮 disabled）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    let releaseRecord: () => void = () => {};
    vi.mocked(vitalSigns.record).mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseRecord = () => resolve({ id: '9100', visitId: 'I20260923000000001' });
        }),
    );
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await selectFirstBed(wrapper);
    await wrapper.find('input[inputmode="decimal"]').setValue('36.5');
    await clickButton(wrapper, '录入体征');
    await flushPromises();
    // 在途窗口：按钮禁用且二次点击经入口守卫零出网
    expect(findButton(wrapper, '录入体征').attributes('disabled')).toBeDefined();
    await clickButton(wrapper, '录入体征');
    await flushPromises();
    expect(vi.mocked(vitalSigns.record)).toHaveBeenCalledTimes(1);
    releaseRecord();
    await flushPromises();
    wrapper.unmount();
  });

  it('超生理极限提示 4xx 口径文案（NS-1005 detail 原文透出）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    vi.mocked(vitalSigns.record).mockRejectedValue({
      errorCode: 'NS-1005',
      detail: '体温超出生理极限',
    });
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await selectFirstBed(wrapper);
    await wrapper.find('input[inputmode="decimal"]').setValue('41.5');
    await clickButton(wrapper, '录入体征');
    await flushPromises();
    expect(vi.mocked(ElMessage.error)).toHaveBeenCalledWith('体温超出生理极限');
    wrapper.unmount();
  });

  it('待复核列表确认后移除该行且仅调用一次', async () => {
    vi.mocked(vitalSigns.pendingReview).mockResolvedValue([
      {
        id: '9200',
        visitId: 'I20260923000000001',
        patientId: '1932000000000000001',
        wardId: 'W01',
        measuredAt: '2026-09-23T08:00:00',
        temperature: 37.8,
        source: 'IOT',
        reviewStatus: 'PENDING_REVIEW',
      },
    ]);
    vi.mocked(vitalSigns.confirm).mockResolvedValue({
      id: '9200',
      reviewStatus: 'CONFIRMED',
    });
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('37.8');
    // 行级移除由 fuy-flip TransitionGroup 承载（锚点⑤：flip 机器证据；断言须在确认前取证
    // ——确认后清单清空连同表格整体卸载，时序错即取证不到）
    const pendingGroup = wrapper
      .findAllComponents({ name: 'TransitionGroup' })
      .find((group) => group.classes().includes('ward-pending-tbody'));
    expect(pendingGroup).toBeDefined();
    expect(pendingGroup?.props('name')).toBe('fuy-flip');
    // 待复核计数>0 时锚胶囊挂印泥朱形态与呼吸点（实时待办语义）
    const reviewPill = wrapper.find('.ward-board-review-anchor');
    expect(reviewPill.classes()).toContain('fuy-status-pill--danger');
    expect(reviewPill.find('.fuy-live-dot').exists()).toBe(true);
    await clickButton(wrapper, '确认');
    await flushPromises();
    expect(vi.mocked(vitalSigns.confirm)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(vitalSigns.confirm)).toHaveBeenCalledWith('9200');
    // 该行移除（fuy-flip leave 语义，值不再呈现）
    expect(wrapper.text()).not.toContain('37.8');
    wrapper.unmount();
  });

  it('体温单按部位渲染符号类名（腋温 fuy-temp-x / 口温 fuy-temp-dot / 短绌填充线与起止竖线）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    vi.mocked(chart.query).mockResolvedValue({
      visitId: 'I20260923000000001',
      chartMonth: currentMonth(),
      vitals: [
        {
          id: '9001',
          entryTime: localIso([22, 8]),
          entryType: 'VITAL',
          typeKey: 'AXILLARY',
          vitalRef: '9001',
        },
        {
          id: '9002',
          entryTime: localIso([22, 12]),
          entryType: 'VITAL',
          typeKey: 'ORAL',
          vitalRef: '9002',
        },
      ],
      specialEvents: [
        {
          id: '9500',
          entryTime: localIso([22, 6]),
          entryType: 'SPECIAL_EVENT',
          specialEventType: 'PULSE_DEFICIT_START',
        },
        {
          id: '9501',
          entryTime: localIso([22, 14]),
          entryType: 'SPECIAL_EVENT',
          specialEventType: 'PULSE_DEFICIT_END',
        },
      ],
      dailyValues: [],
    });
    vi.mocked(vitalSigns.list).mockResolvedValue([
      { id: '9001', temperature: 36.5, tempSite: 'AXILLARY', pulse: 80 },
      { id: '9002', temperature: 37, tempSite: 'ORAL' },
    ]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await selectFirstBed(wrapper);
    await flushPromises();
    // 符号类名契约（§5.3 冻结，机器判据）
    expect(wrapper.find('.fuy-temp-x').exists()).toBe(true);
    expect(wrapper.find('.fuy-temp-dot').exists()).toBe(true);
    // 脉搏短绌窗口（06:00–14:00）内 08:00 脉率点画填充线
    expect(wrapper.find('.fuy-temp-deficit-line').exists()).toBe(true);
    // 短绌起止竖线（§5.6 时段事件：起止时点各画一条红竖线，R1 finding ②）
    expect(wrapper.find('.fuy-event-line--deficit-start').exists()).toBe(true);
    expect(wrapper.find('.fuy-event-line--deficit-end').exists()).toBe(true);
    // 9001 两值齐备但 36.5℃（28 行）与 80 次/分（20 行）落点不同格——常规形态不画重叠红圈
    // （S7 坐标重合判定防假阳性，R1 finding ①）
    expect(wrapper.find('.fuy-temp-overlap-ring').exists()).toBe(false);
    wrapper.unmount();
  });

  it('体温脉搏同格重叠渲染红圈、不同格不渲染（S7 坐标重合判定防假阳性）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    vi.mocked(chart.query).mockResolvedValue({
      visitId: 'I20260923000000001',
      chartMonth: currentMonth(),
      vitals: [
        {
          id: '9001',
          entryTime: localIso([22, 8]),
          entryType: 'VITAL',
          typeKey: 'AXILLARY',
          vitalRef: '9001',
        },
        {
          id: '9002',
          entryTime: localIso([22, 12]),
          entryType: 'VITAL',
          typeKey: 'AXILLARY',
          vitalRef: '9002',
        },
      ],
      specialEvents: [],
      dailyValues: [],
    });
    // 9001：37℃ 与 60 次/分吸附同一小格行（共格轴均第 25 行）→ 坐标重合画红圈；
    // 9002：37℃ 与 80 次/分（第 25 行 vs 第 20 行）不同格 → 不画（R1 finding ①）
    vi.mocked(vitalSigns.list).mockResolvedValue([
      { id: '9001', temperature: 37, tempSite: 'AXILLARY', pulse: 60 },
      { id: '9002', temperature: 37, tempSite: 'AXILLARY', pulse: 80 },
    ]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await selectFirstBed(wrapper);
    await flushPromises();
    // 双向断言：仅重合点渲染红圈（fuy-temp-overlap-ring，§5.3 S7）
    expect(wrapper.findAll('.fuy-temp-overlap-ring')).toHaveLength(1);
    wrapper.unmount();
  });

  it('评估量表提交后展示总分与高危标记（totalScore=50 / HIGH）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    vi.mocked(assessments.scales).mockResolvedValue([
      {
        scaleType: 'BRADEN',
        itemCodes: ['PERCEPTION', 'MOISTURE'],
        itemLabels: ['知觉感受', '潮湿程度'],
        choices: { PERCEPTION: [1, 2, 3, 4], MOISTURE: [1, 2, 3, 4] },
        totalRule: 'SUM',
      },
    ]);
    vi.mocked(assessments.create).mockResolvedValue({
      id: '9300',
      visitId: 'I20260923000000001',
      scaleType: 'BRADEN',
      totalScore: 50,
      riskLevel: 'HIGH',
      assessedAt: '2026-09-23T10:00:00',
    });
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await selectFirstBed(wrapper);
    await flushPromises();
    // 逐条目作答（radio group emit 回填 v-model——存量 spec 的组件事替身口径）
    const groups = wrapper.findAllComponents({ name: 'ElRadioGroup' });
    expect(groups.length).toBe(2);
    for (const group of groups) {
      await group.vm.$emit('update:modelValue', 3);
    }
    await clickButton(wrapper, '提交评估');
    await flushPromises();
    expect(vi.mocked(assessments.create)).toHaveBeenCalledTimes(1);
    // 结果条：总分 50 + 「高风险」文案 + 高危容器类（§3.9 契约）
    expect(wrapper.text()).toContain('50');
    expect(wrapper.text()).toContain('高风险');
    expect(wrapper.find('.fuy-assess-result--high').exists()).toBe(true);
    wrapper.unmount();
  });

  it('切换患者复位体征/评估/出入量草稿，未提交数据不跨患者续提（FE-A2-01）', async () => {
    // 双患者卡墙：01 床留草稿后切 02 床，三处草稿必须复位
    vi.mocked(wardPatients.list).mockResolvedValue([
      patientMock({ bedNo: '01', visitId: 'I20260923000000001' }),
      patientMock({ bedNo: '02', visitId: 'I20260923000000002' }),
    ]);
    vi.mocked(assessments.scales).mockResolvedValue([
      {
        scaleType: 'BRADEN',
        itemCodes: ['PERCEPTION', 'MOISTURE'],
        itemLabels: ['知觉感受', '潮湿程度'],
        choices: { PERCEPTION: [1, 2, 3, 4], MOISTURE: [1, 2, 3, 4] },
        totalRule: 'SUM',
      },
    ]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await wrapper.find('.ward-bed-card').trigger('click');
    await flushPromises();
    // 01 床患者：三处草稿各留数据（体征体温、出入量明细、量表逐条作答），均不提交
    await wrapper.find('input[inputmode="decimal"]').setValue('36.5');
    await clickButton(wrapper, '出入量快录');
    await wrapper.find('input[placeholder="项目编码"]').setValue('FOOD-MILK');
    await wrapper.find('input[placeholder="数量"]').setValue('150');
    for (const group of wrapper.findAllComponents({ name: 'ElRadioGroup' })) {
      await group.vm.$emit('update:modelValue', 3);
    }
    // 切换 02 床患者：草稿不得跨患者滞留（续提即归档他人名下——医疗差错级）
    await wrapper.findAll('.ward-bed-card')[1].trigger('click');
    await flushPromises();
    // 体征草稿复位（体温值清空回初始态）
    expect((wrapper.find('input[inputmode="decimal"]').element as HTMLInputElement).value).toBe('');
    // 出入量草稿复位（项目编码清空）
    expect((wrapper.find('input[placeholder="项目编码"]').element as HTMLInputElement).value).toBe(
      '',
    );
    // 评估草稿复位（量表条目回到未作答态）
    expect(
      wrapper
        .findAllComponents({ name: 'ElRadioGroup' })
        .every((group) => !group.props('modelValue')),
    ).toBe(true);
    // 行为兜底：复位后空草稿提交均被显式校验拦截零出网（不给 02 床患者归档 01 床数据）
    await clickButton(wrapper, '录入体征');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请至少录入一项体征数据');
    expect(vi.mocked(vitalSigns.record)).not.toHaveBeenCalled();
    await clickButton(wrapper, '记录出入量');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写项目编码');
    expect(vi.mocked(ioRecords.create)).not.toHaveBeenCalled();
    await clickButton(wrapper, '提交评估');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '存在未作答条目，请完成全部条目后提交',
    );
    expect(vi.mocked(assessments.create)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('切换患者复位特殊事件草稿，类型/备注不跨患者滞留（BUG-15 同类）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      patientMock({ bedNo: '01', visitId: 'I20260923000000001' }),
      patientMock({ bedNo: '02', visitId: 'I20260923000000002' }),
    ]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await selectFirstBed(wrapper);
    // 01 床留草稿：类型切「手术」+ 备注填值，不提交
    const evtTypeSelect = wrapper
      .findAllComponents({ name: 'ElSelect' })
      .find((node) => node.classes().includes('ward-chart-event-type'));
    if (!evtTypeSelect) {
      throw new Error('未找到特殊事件类型选择器');
    }
    await evtTypeSelect.vm.$emit('update:modelValue', 'SURGERY');
    await wrapper.find('input[placeholder="备注（选填）"]').setValue('术前准备完毕');
    // 切换 02 床患者：草稿回初始态（类型 ADMISSION + 备注清空）
    await wrapper.findAll('.ward-bed-card')[1].trigger('click');
    await flushPromises();
    const evtTypeAfter = wrapper
      .findAllComponents({ name: 'ElSelect' })
      .find((node) => node.classes().includes('ward-chart-event-type'));
    if (!evtTypeAfter) {
      throw new Error('未找到特殊事件类型选择器');
    }
    expect(evtTypeAfter.props('modelValue')).toBe('ADMISSION');
    expect(
      (wrapper.find('input[placeholder="备注（选填）"]').element as HTMLInputElement).value,
    ).toBe('');
    wrapper.unmount();
  });

  it('切换患者清空评估结果条残留，前次患者判级不滞留新患者名下', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      patientMock({ bedNo: '01', visitId: 'I20260923000000001' }),
      patientMock({ bedNo: '02', visitId: 'I20260923000000002' }),
    ]);
    vi.mocked(assessments.scales).mockResolvedValue([
      {
        scaleType: 'BRADEN',
        itemCodes: ['PERCEPTION'],
        itemLabels: ['知觉感受'],
        choices: { PERCEPTION: [1, 2, 3, 4] },
        totalRule: 'SUM',
      },
    ]);
    vi.mocked(assessments.create).mockResolvedValue({
      id: '9301',
      visitId: 'I20260923000000001',
      scaleType: 'BRADEN',
      totalScore: 50,
      riskLevel: 'HIGH',
      assessedAt: '2026-09-23T10:00:00',
    });
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await selectFirstBed(wrapper);
    for (const group of wrapper.findAllComponents({ name: 'ElRadioGroup' })) {
      await group.vm.$emit('update:modelValue', 3);
    }
    await clickButton(wrapper, '提交评估');
    await flushPromises();
    // 01 床提交后结果条在册（高风险展示契约 §3.9）
    expect(wrapper.find('.ward-assess-result').exists()).toBe(true);
    // 切换 02 床患者：结果条必须随上下文清空（「高风险」挂他人名下=误导判级）
    await wrapper.findAll('.ward-bed-card')[1].trigger('click');
    await flushPromises();
    expect(wrapper.find('.ward-assess-result').exists()).toBe(false);
    wrapper.unmount();
  });

  it('切换患者后在途回包比对当前选中患者，过期回包丢弃（EX-45/FE-A1-04）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      patientMock({ bedNo: '01', visitId: 'I20260923000000001' }),
      patientMock({ bedNo: '02', visitId: 'I20260923000000002' }),
    ]);
    // 详情按 visitId 区分患者（patientId 随之对齐，体征查询窗口锚定各自患者）
    vi.mocked(wardPatients.detail).mockImplementation((visitId) =>
      Promise.resolve(
        detailMock({
          visitId,
          patientId:
            visitId === 'I20260923000000001' ? '1932000000000000001' : '1932000000000000002',
        }),
      ),
    );
    // 体征查询按患者挂起：手动放行制造「旧患者慢回包晚到」竞态窗口
    const resolvers: Record<string, Array<(rows: VitalSignVO[]) => void>> = {};
    vi.mocked(vitalSigns.list).mockImplementation(
      (params) =>
        new Promise((resolve) => {
          const key = String(params.patientId);
          (resolvers[key] ??= []).push(resolve);
        }),
    );
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await wrapper.find('.ward-bed-card').trigger('click');
    await flushPromises();
    // 立即切换 02 床：两患者在途请求并存
    await wrapper.findAll('.ward-bed-card')[1].trigger('click');
    await flushPromises();
    // 02 床回包先到（近 24h 空列表 + 月窗空列表）：详情面板无 01 床体温值
    for (const resolve of resolvers['1932000000000000002'] ?? []) {
      resolve([]);
    }
    await flushPromises();
    expect(wrapper.text()).not.toContain('38.9');
    // 01 床慢回包后到：过期回包必须丢弃，不得覆盖 02 床的体征简报
    for (const resolve of resolvers['1932000000000000001'] ?? []) {
      resolve([{ id: '9001', temperature: 38.9, tempSite: 'AXILLARY', pulse: 90 }]);
    }
    await flushPromises();
    expect(wrapper.text()).not.toContain('38.9');
    wrapper.unmount();
  });

  it('任务列表逾期标记渲染且完成出网一次（overdueFlag=true）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock({ bedNo: '03-01' })]);
    vi.mocked(tasks.list).mockResolvedValue([
      {
        id: '9400',
        taskNo: 'T20260923001',
        patientId: '1932000000000000001',
        visitId: 'I20260923000000001',
        wardId: 'W01',
        bedNo: '03-01',
        taskType: 'TURN',
        planTime: '2026-09-23 06:00:00',
        overdueFlag: true,
        status: 'PENDING',
      },
    ]);
    vi.mocked(tasks.complete).mockResolvedValue({
      taskNo: 'T20260923001',
      status: 'COMPLETED',
    });
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 逾期契约：行类 + 「逾期」文案（仍可完成——逾期为动作标记不改状态）
    expect(wrapper.text()).toContain('逾期');
    expect(wrapper.find('tr.fuy-task-overdue').exists()).toBe(true);
    await clickButton(wrapper, '完成');
    await flushPromises();
    expect(vi.mocked(tasks.complete)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(tasks.complete)).toHaveBeenCalledWith('T20260923001');
    wrapper.unmount();
  });

  it('交接班双签：DRAFT 态「完成交接」可点，COMPLETED 态置灰且文案切换', async () => {
    // 场景一：DRAFT 可点
    vi.mocked(handovers.list).mockResolvedValue([
      {
        id: '9600',
        handoverNo: 'HD20260923001',
        wardId: 'W01',
        shiftCode: 'DAY',
        status: 'DRAFT',
        patientSummary: { total: 8, criticalCount: 1, specialCount: 2 },
        sbarSituation: '在区 8 人',
        pendingItems: [],
      },
    ]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    const draftButton = findButton(wrapper, '完成交接');
    expect(draftButton.attributes('disabled')).toBeUndefined();
    // 摘要标签映射（后端权威 ShiftHandoverVO javadoc：SPECIAL=特级 / CRITICAL=病重，防互换回归 R1 finding ④）
    expect(wrapper.text()).toContain('特级 2');
    expect(wrapper.text()).toContain('病重 1');
    expect(wrapper.text()).not.toContain('病危');
    // 场景二：COMPLETED 置灰 + 文案「已完成交接」
    vi.mocked(handovers.list).mockResolvedValue([
      {
        id: '9600',
        handoverNo: 'HD20260923001',
        wardId: 'W01',
        shiftCode: 'DAY',
        status: 'COMPLETED',
        patientSummary: { total: 8 },
        sbarSituation: '在区 8 人',
        pendingItems: [],
      },
    ]);
    const wrapper2 = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    const doneButton = findButton(wrapper2, '已完成交接');
    expect(doneButton.attributes('disabled')).toBeDefined();
    wrapper.unmount();
    wrapper2.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(WardBoardView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 nursing:ward:btn:vital 时体征录入与待复核操作隐藏且不扰他码（D-34）', async () => {
    // 仅含任务码 #27 的会话：体征族（#25）隐藏、任务族（#27）可见——同视图多码互不干扰
    seedAuthSessionWithPermissions(['nursing:ward:btn:task']);
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // #25 vital 族三按钮（录入体征为常驻入口；确认/驳回随待复核行数据出位）无码全隐藏
    expect(buttonTexts).not.toContain('录入体征');
    // #27 task 族「生成常规任务」不受 #25 缺码影响
    expect(buttonTexts).toContain('生成常规任务');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 nursing:ward:btn:vital 时体征录入与待复核操作可见', async () => {
    vi.mocked(vitalSigns.pendingReview).mockResolvedValue([
      { id: '9001', measuredAt: '2026-09-23T07:00:00', temperature: 36.8, source: 'IOT' },
    ]);
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('录入体征');
    // 待复核行出位后确认/驳回（与录入入口同码）可见
    expect(buttonTexts).toContain('确认');
    expect(buttonTexts).toContain('驳回');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话无 nursing:ward:btn:task 时任务/责任分配族隐藏且不扰他码（D-34）', async () => {
    vi.mocked(tasks.list).mockResolvedValue([
      {
        id: '9400',
        taskNo: 'T20260923001',
        patientId: '1932000000000000001',
        visitId: 'I20260923000000001',
        wardId: 'W01',
        bedNo: '03-01',
        taskType: 'TURN',
        planTime: '2026-09-23 06:00:00',
        status: 'PENDING',
      },
    ]);
    // 仅含体征码 #25 的会话：任务族（#27）隐藏、体征族（#25）可见——互不干扰反向验证
    seedAuthSessionWithPermissions(['nursing:ward:btn:vital']);
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // #27 task 族五口（生成/认领/完成/取消/责任分配增删）无码全隐藏
    expect(buttonTexts).not.toContain('生成常规任务');
    expect(buttonTexts).not.toContain('认领');
    expect(buttonTexts).not.toContain('完成');
    expect(buttonTexts).not.toContain('新增分配');
    // #25 vital 族「录入体征」不受 #27 缺码影响
    expect(buttonTexts).toContain('录入体征');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 nursing:ward:btn:task 时任务与责任分配入口可见', async () => {
    vi.mocked(tasks.list).mockResolvedValue([
      {
        id: '9400',
        taskNo: 'T20260923001',
        patientId: '1932000000000000001',
        visitId: 'I20260923000000001',
        wardId: 'W01',
        bedNo: '03-01',
        taskType: 'TURN',
        planTime: '2026-09-23 06:00:00',
        status: 'PENDING',
      },
    ]);
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('生成常规任务');
    expect(buttonTexts).toContain('认领');
    expect(buttonTexts).toContain('完成');
    expect(buttonTexts).toContain('新增分配');
    wrapper.unmount();
  });

  /* ==================== 暖纸卷宗换脸锚点（逐页蓝图 甲-1 §7，锚点①~⑥） ==================== */

  it('锚点①门牌页首三锚：衬线标题「护士站」+英文小注装饰层+当班护士批注行', async () => {
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.find('.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-title').text()).toBe('护士站');
    const en = wrapper.find('.fuy-page-en');
    expect(en.text()).toBe('NURSING STATION');
    expect(en.attributes('aria-hidden')).toBe('true');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('当班护士 李护士');
    expect(note.text()).toContain('班次 白班');
    // 墨规收底（2px border-bottom）由全局 .fuy-page-head 族承载——挂类即机器证据
    wrapper.unmount();
  });

  it('锚点②卷宗区序：床位卡墙 → 体温单卡 → 患者三列 → 页脚批注（体温单升格布局序机器证据）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    const flow = wrapper.find('.ward-board-flow').element as HTMLElement;
    const indexOf = (predicate: (el: Element) => boolean): number =>
      Array.from(flow.children).findIndex(predicate);
    const wallIdx = indexOf((el) => el.classList.contains('ward-board-wall-card'));
    const chartIdx = indexOf((el) => el.classList.contains('ward-board-chart-card'));
    const colsIdx = indexOf((el) => el.classList.contains('ward-board-cols'));
    const footIdx = indexOf((el) => el.classList.contains('fuy-page-foot'));
    expect(wallIdx).toBeGreaterThanOrEqual(0);
    expect(chartIdx).toBeGreaterThan(wallIdx);
    expect(colsIdx).toBeGreaterThan(chartIdx);
    expect(footIdx).toBeGreaterThan(colsIdx);
    // 页脚批注=数据时点语义（禁操作钮，契约 ⑧.8）
    expect(wrapper.find('.fuy-page-foot').text()).toContain('数据时点');
    wrapper.unmount();
  });

  it('锚点③⑥卡墙选中金洗形态类；off-token #e5e7eb 与旧红洗底字面量源码级清零', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.find('.ward-bed-card.is-selected').exists()).toBe(false);
    await selectFirstBed(wrapper);
    expect(wrapper.find('.ward-bed-card.is-selected').exists()).toBe(true);
    wrapper.unmount();
    // 源码级断言：卡墙边 #e5e7eb、高危条旧 rgba(185,28,28,0.08) 均不得残留；
    // 高危洗底取印泥朱 alpha 派生 rgba(180,35,24,0.08)（蓝图口径，十值纪律内）
    const source = readFileSync(join(import.meta.dirname, 'WardBoardView.vue'), 'utf-8');
    expect(source).not.toContain('#e5e7eb');
    expect(source).not.toContain('185, 28, 28');
    expect(source).toContain('rgba(180, 35, 24, 0.08)');
  });

  it('锚点④病区真数据三态：listOrgs({type:"WARD"}) 在途骨架+选择器禁用，到达落首项并拉病区面', async () => {
    let releaseWards: () => void = () => {};
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          releaseWards = () => resolve(WARD_SEED);
        }),
    );
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 请求参数断言：假常量清零后唯一真数据源（册级总则 G4）
    expect(vi.mocked(listOrgs)).toHaveBeenCalledWith({ type: 'WARD' });
    // 加载骨架（卡墙 240px CLS 锁档）+ 病区选择器在途禁用 + 病区面零出网
    expect(wrapper.find('.ward-wall-sk').exists()).toBe(true);
    const wardSelect = wrapper
      .findAllComponents({ name: 'ElSelect' })
      .find((select) => select.classes().includes('ward-board-ward'));
    expect(wardSelect?.props('disabled')).toBe(true);
    expect(vi.mocked(wardPatients.list)).not.toHaveBeenCalled();
    // 清单到达：选择器解禁、选项=种子两病区（label=「名称（编码）」）、默认落首项并拉病区面
    releaseWards();
    await flushPromises();
    expect(wrapper.find('.ward-wall-sk').exists()).toBe(false);
    expect(wardSelect?.props('disabled')).toBe(false);
    const options = wardSelect?.findAllComponents({ name: 'ElOption' }) ?? [];
    expect(options.map((option) => option.props('value'))).toEqual(['W01', '1001']);
    expect(options[0]?.props('label')).toBe('演示病区（W01）');
    expect(vi.mocked(wardPatients.list)).toHaveBeenCalledWith('W01');
    wrapper.unmount();
  });

  it('锚点④病区真数据三态：清单失败给「说问题+给恢复」恢复态，重新加载后主链恢复', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('网络不可用'));
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('病区清单加载失败');
    expect(wrapper.text()).toContain('重新加载');
    // 恢复前零病区面出网（一览必须锚定真实病区）
    expect(vi.mocked(wardPatients.list)).not.toHaveBeenCalled();
    await clickButton(wrapper, '重新加载');
    await flushPromises();
    expect(wrapper.text()).not.toContain('病区清单加载失败');
    expect(vi.mocked(wardPatients.list)).toHaveBeenCalledWith('W01');
    wrapper.unmount();
  });

  it('锚点④病区真数据三态：空清单给「暂无可用病区」空态脸且零病区面出网', async () => {
    vi.mocked(listOrgs).mockResolvedValue([]);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('暂无可用病区');
    expect(vi.mocked(wardPatients.list)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('锚点⑤体征录入成功盖钤印「已录入」（动词结果，is-show 落章形态）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    vi.mocked(vitalSigns.record).mockResolvedValue({ id: '9101', visitId: 'I20260923000000001' });
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await selectFirstBed(wrapper);
    await wrapper.find('input[inputmode="decimal"]').setValue('36.5');
    await clickButton(wrapper, '录入体征');
    await flushPromises();
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.classes()).toContain('is-show');
    expect(stamp.text()).toBe('已录入');
    expect(stamp.attributes('aria-live')).toBe('polite');
    wrapper.unmount();
  });

  it('锚点⑦性能：卡墙超 120 卡按床号段分组折叠（组头「01-10 床」石规签行，点击折叠本段）', async () => {
    const many = Array.from({ length: 121 }, (_, index) => {
      const bedNo = String(index + 1).padStart(3, '0');
      return patientMock({ bedNo, visitId: `I2026092300000000${bedNo}` });
    });
    vi.mocked(wardPatients.list).mockResolvedValue(many);
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('01-10 床');
    const heads = wrapper.findAll('.ward-wall-group-head');
    // 121 床 → 十位段 01-10…111-120 共 12 组 + 121-130 尾段 1 组
    expect(heads.length).toBe(13);
    expect(wrapper.findAll('.ward-bed-card')).toHaveLength(121);
    // 折叠交互：点击首段组头后该段 10 卡收起
    const firstHead = heads[0];
    if (!firstHead) {
      throw new Error('未找到卡墙分组组头');
    }
    await firstHead.trigger('click');
    await flushPromises();
    expect(wrapper.findAll('.ward-bed-card')).toHaveLength(111);
    wrapper.unmount();
  });
});
