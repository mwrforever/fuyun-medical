// 护士工作站页单测（M05 前端面，设计文档 §3 八区块）：病区一览床位序渲染（spec 冻结语序
// 断言）、入区登记显式校验零出网（visit 号 14 位格式）、体征录入非整数显式校验零出网、
// 录入在途守卫双击零二次出网、NS-1005 超生理极限 detail 透出（4xx 口径）、待复核确认后
// 该行移除、体温单符号类名契约（fuy-temp-x/fuy-temp-dot/fuy-temp-deficit-line/短绌起止
// 竖线/重叠红圈同格判定，机器判据）、评估总分与高危容器类（fuy-assess-result--high）、任务
// 逾期行类（fuy-task-overdue）与完成出网、交接班双签 DRAFT 可点 / COMPLETED 置灰与摘要
// 特级/病重标签映射。api mock 承载，不打真实网络；会话经
// sessionStorage 种子恢复（当班护士=登录用户）。
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
import type { WardPatientDetailVO, WardPatientVO } from '@/api/nursing';
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
  CONDITION_TAG_OPTIONS: [
    { code: 'CRITICAL', label: '病危' },
    { code: 'SEVERE', label: '病重' },
    { code: 'NEW', label: '新入' },
    { code: 'SURGERY', label: '手术' },
    { code: 'DELIVERY', label: '分娩' },
  ],
  SPECIAL_EVENT_OPTIONS: [{ code: 'ADMISSION', label: '入院' }],
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  SHIFT_OPTIONS: [
    { code: 'DAY', label: '白班' },
    { code: 'EVENING', label: '小夜班' },
    { code: 'NIGHT', label: '大夜班' },
  ],
  wardPatients: { register: vi.fn(), list: vi.fn(), detail: vi.fn(), remove: vi.fn() },
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
  tasks: { list: vi.fn(), complete: vi.fn(), cancel: vi.fn() },
  handovers: { generate: vi.fn(), complete: vi.fn(), list: vi.fn() },
  pda: { patientSummary: vi.fn(), patrol: vi.fn() },
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

/** 会话种子（auth store 从 sessionStorage 恢复：当班护士=登录用户 u1/李护士） */
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

/** 患者详情（与 patientMock 同上下文；角标/在途任务可覆写） */
function detailMock(partial: Partial<WardPatientDetailVO> = {}): WardPatientDetailVO {
  return {
    wardId: 'W01',
    bedNo: '01',
    patientId: '1932000000000000001',
    visitId: 'I20260923000000001',
    patientName: '张三',
    gender: '男',
    age: 62,
    nursingLevel: 'NORMAL',
    conditionTags: '',
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
      wardPatients.register,
      wardPatients.remove,
      vitalSigns.record,
      vitalSigns.confirm,
      vitalSigns.reject,
      assessments.create,
      tasks.complete,
      tasks.cancel,
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

  it('入区登记表单 visit 号格式非法时提示且零出网（I2026 非法）', async () => {
    const wrapper = mount(WardBoardView, { global: { plugins: [pinia] } });
    await flushPromises();
    await clickButton(wrapper, '入区登记');
    await wrapper.find('input[placeholder="数字编号"]').setValue('1932000000000000002');
    await wrapper.find('input[placeholder="I + 13 位数字"]').setValue('I2026');
    await wrapper.find('input[placeholder="患者姓名"]').setValue('李四');
    await wrapper.find('input[placeholder="如 03-01"]').setValue('05');
    await clickButton(wrapper, '确认登记');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      'visit 号应以 I 开头共 14 位（I+日期+流水），请核对入区单',
    );
    expect(vi.mocked(wardPatients.register)).not.toHaveBeenCalled();
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
});
