// 病区床位图单测（/inpatient/beds，M04 FU-M04-02 前端面 · 暖纸卷宗重设计切片乙-2）：
// ① 五态纸面卡双通道（fuy-bed-card--{state} 契约类=左缘 4px 状态规线锚 + .bed-card-state
//    状态文字徽标=文字通道，两锚逐一成对——旧「整卡实底白字」形态断言随蓝图 G3 裁决删除，
//    按新契约重写且严格度不降：规线类与徽标文字双锚缺一不可）；② 图例计数与卡态同源；
// ③ 转科四阶段口径文案逐字冻结；④ listOrgs 病区清单三态（G4 假常量 WARD_OPTIONS 清零，
//    本页零消费）；⑤ 钤印族（已预占/已占床/消毒完成/维修恢复/已转科完成/已转床——失败与
//    业务拒绝不盖印走 message 批注条）。
// 换脸不换业务：床位状态机/动作启停/权限过滤 actionsOf/占床 prompt 校验文案零变动——消毒
// 完成出网并刷新、转科 execute/changeBed 出网、未选目标零出网、PR-4F 无码全隐藏等业务断言
// 原样保留；占床摘要脱敏口径（visitId+入科时点，姓名不出网）保留。
// api mock 承载零出网（vi.mock 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beds, transfer } from '@/api/inpatient';
import type { BedMapVO, TransferResultVO } from '@/api/inpatient';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import BedMapView from './BedMapView.vue';

vi.mock('@/api/inpatient', () => ({
  BED_STATUS_LABELS: {
    FREE: '空床',
    RESERVED: '预占',
    OCCUPIED: '占床',
    DISINFECTING: '消毒中',
    MAINTENANCE: '维修中',
  },
  beds: {
    map: vi.fn(),
    reserve: vi.fn(),
    assign: vi.fn(),
    release: vi.fn(),
    disinfectDone: vi.fn(),
    maintain: vi.fn(),
    maintainDone: vi.fn(),
  },
  transfer: { execute: vi.fn(), changeBed: vi.fn() },
}));

// 病区清单真数据源（G4）：listOrgs WARD 档替身，WARD_OPTIONS 假常量随清零退场（本页零消费）
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
      prompt: vi.fn().mockResolvedValue({ value: 'I2026092500001' }),
    },
  };
});

/** listOrgs 病区种子（listOrgs WARD 档真数据面单床演示病区） */
const WARD_SEED: OrgVO[] = [{ id: '5', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD' }];

/** 床位行（五态可覆写；占床行携占用就诊摘要） */
function bedMock(partial: Partial<BedMapVO> = {}): BedMapVO {
  return {
    bedId: '901',
    wardId: 'W01',
    bedNo: '01',
    bedAttr: 'NORMAL',
    bedStatus: 'FREE',
    allowGender: 'ALL',
    occupiedVisit: undefined,
    ...partial,
  };
}

/** 五态齐全的床位图（床号 01-05 各一态） */
function fiveStateBeds(): BedMapVO[] {
  return [
    bedMock({ bedId: '901', bedNo: '01', bedStatus: 'FREE' }),
    bedMock({ bedId: '902', bedNo: '02', bedStatus: 'RESERVED' }),
    bedMock({
      bedId: '903',
      bedNo: '03',
      bedStatus: 'OCCUPIED',
      occupiedVisit: {
        visitId: 'I2026092000003',
        patientId: '1932000000000000003',
        admittedAt: '2026-09-20T08:00:00+08:00',
      },
    }),
    bedMock({ bedId: '904', bedNo: '04', bedStatus: 'DISINFECTING' }),
    bedMock({ bedId: '905', bedNo: '05', bedStatus: 'MAINTENANCE' }),
  ];
}

/** 批量床位（分组折叠用例：三位床号 001 起连号） */
function bulkBeds(count: number): BedMapVO[] {
  return Array.from({ length: count }, (_, index) =>
    bedMock({
      bedId: String(900 + index + 1),
      bedNo: String(index + 1).padStart(3, '0'),
      bedStatus: 'FREE',
    }),
  );
}

/** 转科编排出参（前后定位面） */
function transferResultMock(): TransferResultVO {
  return {
    visitId: 'I2026092000003',
    fromWardId: 'W01',
    fromBedId: '903',
    toWardId: 'W01',
    toBedId: '902',
    transferredAt: '2026-09-25T10:00:00+08:00',
  };
}

/** 五态 → 规线类后缀与中文徽标文案对照（乙-2 锚点①双通道逐一成对的断言载体） */
const STATE_CASES: ReadonlyArray<readonly [string, string]> = [
  ['free', '空床'],
  ['reserved', '预占'],
  ['occupied', '占床'],
  ['disinfecting', '消毒中'],
  ['maintenance', '维修中'],
];

/** 按按钮文案点击 */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/** 定位指定床号的床位卡（状态类/操作 select 载体） */
function findBedCard(wrapper: VueWrapper, bedNo: string) {
  return wrapper.findAll('.bed-card').find((card) => card.text().includes(bedNo));
}

describe('病区床位图', () => {
  /** 文件级 Pinia：actionsOf 过滤与 v-perm 读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    for (const fn of [
      beds.map,
      beds.reserve,
      beds.assign,
      beds.release,
      beds.disinfectDone,
      beds.maintain,
      beds.maintainDone,
      transfer.execute,
      transfer.changeBed,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(listOrgs).mockReset().mockResolvedValue(WARD_SEED);
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(beds.map).mockResolvedValue([]);
    // PR-4F #15 后转科转床入口挂元素权限（actionsOf 过滤）：激活 pinia 并播种含码会话，
    // 既有用例语义不变（占床卡动作下拉按权限正常出现，断言零改动）
    pinia = createPinia();
    setActivePinia(pinia);
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, displayName: '李护士', permissions: ['inpatient:bed:btn:change'] },
      }),
    );
  });

  it('病区下拉 listOrgs 三态（乙-2 锚点④）：在途骨架与禁选→清单到达出选项并拉床位图', async () => {
    // 在途（deferred 手控时序）：清单未就绪时卡墙给骨架，病区下拉禁用，床位面零出网
    let releaseWards: () => void = () => {};
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          releaseWards = () => resolve(WARD_SEED);
        }),
    );
    const wrapper = mount(BedMapView);
    await flushPromises();
    expect(listOrgs).toHaveBeenCalledWith({ type: 'WARD' });
    expect(wrapper.find('.bed-wall-sk').exists()).toBe(true);
    expect(wrapper.find('select[aria-label="病区选择"]').attributes('disabled')).toBeDefined();
    expect(beds.map).not.toHaveBeenCalled();

    // 清单到达：解禁，选项=种子病区（出网值=真实病区编码），装配链续拉床位图
    releaseWards();
    await flushPromises();
    const wardSelect = wrapper.find('select[aria-label="病区选择"]');
    expect(wardSelect.attributes('disabled')).toBeUndefined();
    const options = wardSelect.findAll('option');
    expect(options.map((o) => o.element.value)).toEqual(['W01']);
    expect(options[0]?.text()).toBe('演示病区（W01）');
    expect(beds.map).toHaveBeenCalledWith('W01');
    wrapper.unmount();
  });

  it('病区下拉 listOrgs 失败态：恢复行说问题给重入（不伪装空选项），重新加载成功后回床位卡墙', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('network down'));
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const errorRow = wrapper.find('.bed-map-boot-error');
    expect(errorRow.exists()).toBe(true);
    expect(errorRow.attributes('role')).toBe('alert');
    expect(errorRow.text()).toContain('病区清单加载失败');
    expect(errorRow.text()).toContain('重新加载');
    // 重入：listOrgs 二次出网，成功后恢复行消失、床位卡墙就绪
    await clickButton(wrapper, '重新加载');
    await flushPromises();
    expect(listOrgs).toHaveBeenCalledTimes(2);
    expect(wrapper.find('.bed-map-boot-error').exists()).toBe(false);
    expect(wrapper.find('.bed-map-wall').exists()).toBe(true);
    wrapper.unmount();
  });

  it('门牌页首三锚（乙-2 蓝图①）：衬线标题「病区床位图」+ BED BOARD 小注 + 事件投影批注行与操作员时刻', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('病区床位图');
    expect(wrapper.find('.fuy-page-en').text()).toBe('BED BOARD');
    // 批注行=「床位状态机经后端事件投影 · 谁 · 时刻」（蓝图钦定口径；空值经操作员缺省兜底）
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('床位状态机经后端事件投影');
    expect(note.text()).toContain('操作员 李护士');
    expect(note.find('time').exists()).toBe(true);
    // 页脚批注（契约 ⑧.8）：数据时点 + 手动刷新取数语义，禁放操作钮
    const foot = wrapper.find('.fuy-page-foot');
    expect(foot.exists()).toBe(true);
    expect(foot.text()).toContain('数据时点');
    expect(foot.text()).toContain('手动刷新取数');
    // 卡墙卡头：床位计数与当前病区（空值 — 占位）
    const meta = wrapper.find('.bed-map-wall-meta');
    expect(meta.text()).toContain('床位 5');
    expect(meta.text()).toContain('W01');
    wrapper.unmount();
  });

  it('五态纸面卡双通道（乙-2 锚点①）：fuy-bed-card--{state} 规线类与状态文字徽标逐一成对', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    for (const [state, label] of STATE_CASES) {
      const card = wrapper.find(`.bed-card.fuy-bed-card--${state}`);
      // 通道一：规线锚——契约状态类在卡上（左缘 4px 状态规线取色经 --fuy-color-bed-* 语义 token）
      expect(card.exists()).toBe(true);
      // 通道二：文字通道——状态徽标文案与词表逐一成对（双通道铁律：色不单独承义）
      expect(card.find('.bed-card-state').text()).toBe(label);
    }
    expect(wrapper.findAll('.bed-card')).toHaveLength(5);
    // 图例五色点（双通道既有：五色点+文字+计数）
    expect(wrapper.find('.bed-map-legend--free .bed-map-legend-dot').exists()).toBe(true);
    // 单病区 ≤120 床：无组头平铺（G5 分组阈值不触发）
    expect(wrapper.findAll('.bed-wall-group-head')).toHaveLength(0);
    wrapper.unmount();
  });

  it('图例计数与卡态同源（乙-2 锚点②）：五态图例计数=对应状态类卡数', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    for (const [state, label] of STATE_CASES) {
      const cardCount = wrapper.findAll(`.bed-card.fuy-bed-card--${state}`).length;
      const legendItem = wrapper.find(`.bed-map-legend--${state}`);
      expect(legendItem.text()).toContain(label);
      expect(legendItem.find('.fuy-num').text()).toBe(String(cardCount));
    }
    wrapper.unmount();
  });

  it('占床床位显示占用患者摘要（visitId+入科时点，姓名脱敏不出网）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const occupiedCard = findBedCard(wrapper, '03');
    expect(occupiedCard).toBeDefined();
    const cardText = occupiedCard?.text() ?? '';
    // 占床摘要：就诊号直显；患者姓名按脱敏口径不出网（无姓名字段）
    expect(cardText).toContain('I2026092000003');
    expect(cardText).toContain('在院');
    expect(cardText).toContain('入科');
    wrapper.unmount();
  });

  it('床位空态脸（G6）：暂无床位主句+给下一步说明，默认插画 el-empty 清零', async () => {
    const wrapper = mount(BedMapView);
    await flushPromises();
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.attributes('role')).toBe('status');
    expect(empty.text()).toContain('暂无床位');
    expect(empty.text()).toContain('可切换病区或点击刷新');
    // el-empty 默认纸箱插画随空态脸收编清零（契约 ⑥ 反向断言）
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    wrapper.unmount();
  });

  it('消毒完成操作出网并刷新床位图，成功盖钤印「消毒完成」（乙-2 锚点⑤）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const disinfectingCard = findBedCard(wrapper, '04');
    expect(disinfectingCard).toBeDefined();
    // 床位卡操作 select 选择「消毒完成」（值=动作语义键，native select 保留）
    await disinfectingCard?.find('select').setValue('disinfectDone');
    await flushPromises();
    expect(beds.disinfectDone).toHaveBeenCalledWith('904');
    // 成功反馈=钤印（.fuy-stamp-toast v-if 挂载制 + 静态 is-show，动词结果文案）
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.classes()).toContain('is-show');
    expect(stamp.text()).toContain('消毒完成');
    // 床位图重载（map 第二次调用）
    expect(beds.map).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('消毒完成失败不盖钤印：业务拒绝走 message 批注条（钤印永不承失败）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    vi.mocked(beds.disinfectDone).mockRejectedValue({ detail: '床位状态不允许该操作' });
    const wrapper = mount(BedMapView);
    await flushPromises();
    const disinfectingCard = findBedCard(wrapper, '04');
    await disinfectingCard?.find('select').setValue('disinfectDone');
    await flushPromises();
    expect(vi.mocked(ElMessage.error)).toHaveBeenCalledWith('床位状态不允许该操作');
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    wrapper.unmount();
  });

  it('空床预占成功盖钤印「已预占」（确认弹窗通过后出网空对象契约）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const freeCard = findBedCard(wrapper, '01');
    await freeCard?.find('select').setValue('reserve');
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalled();
    // 预占不绑定就诊主体，契约形状固化空对象（BedReserveRequest）
    expect(beds.reserve).toHaveBeenCalledWith('901', {});
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toContain('已预占');
    expect(beds.map).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('释放预占/转维修/维修恢复动作族出网并盖对应钤印（已释放/已转维修/维修恢复）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    // 释放预占（RESERVED→FREE）
    await findBedCard(wrapper, '02')?.find('select').setValue('release');
    await flushPromises();
    expect(beds.release).toHaveBeenCalledWith('902');
    expect(wrapper.find('.fuy-stamp-toast').text()).toContain('已释放');
    // 维修恢复（MAINTENANCE→FREE）
    await findBedCard(wrapper, '05')?.find('select').setValue('maintainDone');
    await flushPromises();
    expect(beds.maintainDone).toHaveBeenCalledWith('905');
    expect(wrapper.find('.fuy-stamp-toast').text()).toContain('维修恢复');
    // 转维修（FREE→MAINTENANCE）
    await findBedCard(wrapper, '01')?.find('select').setValue('maintain');
    await flushPromises();
    expect(beds.maintain).toHaveBeenCalledWith('901');
    expect(wrapper.find('.fuy-stamp-toast').text()).toContain('已转维修');
    // 每次动作后床位图重载：首拉 + 三次动作重载
    expect(beds.map).toHaveBeenCalledTimes(4);
    wrapper.unmount();
  });

  it('占床操作携 visit 号出网（prompt 输入经 14 位格式校验文案冻结），成功盖钤印「已占床」', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const freeCard = findBedCard(wrapper, '01');
    await freeCard?.find('select').setValue('assign');
    await flushPromises();
    // ElMessageBox.prompt 替身返回 14 位 visit 号（模块级 mockResolvedValue 种子）
    expect(beds.assign).toHaveBeenCalledWith('901', { visitId: 'I2026092500001' });
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toContain('已占床');
    expect(beds.map).toHaveBeenCalledTimes(2);
    // prompt 校验文案冻结（visit 号 I+13 位正则，M02 冻结）：合法放行、非法给口径原文
    const promptArgs = vi.mocked(ElMessageBox.prompt).mock.calls[0] as unknown as [
      string,
      string,
      { inputValidator: (input: string) => string | boolean },
    ];
    expect(promptArgs[1]).toBe('床位占床');
    const validator = promptArgs[2].inputValidator;
    expect(validator('I2026092500001')).toBe(true);
    expect(validator('J2026092500001')).toBe('visit 号应以 I 开头共 14 位（I+日期+流水）');
    expect(validator('I123')).toBe('visit 号应以 I 开头共 14 位（I+日期+流水）');
    wrapper.unmount();
  });

  it('转科弹窗四阶段口径文案逐字冻结（乙-2 锚点③）且弹窗挂 .fuy-dialog', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const occupiedCard = findBedCard(wrapper, '03');
    await occupiedCard?.find('select').setValue('transferDialog');
    await flushPromises();
    // 弹层规格（G2）：转科转床弹窗挂 .fuy-dialog（衬线标题脸）
    expect(wrapper.find('.fuy-dialog').exists()).toBe(true);
    // 四阶段口径文案逐字冻结（转科编排全由后端承载，前端只承提示）
    expect(wrapper.find('.bed-transfer-stages').text()).toBe(
      '转科按四阶段编排执行：① 医嘱停嘱截断 → ② 在途执行三分 → ③ 床位流转 → ④ 事件通知；同病区转床仅执行床位流转（无停嘱）。',
    );
    wrapper.unmount();
  });

  it('转科弹窗提交出网（transfer.execute 携目标病区与床位），成功盖钤印「已转科完成」', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    vi.mocked(transfer.execute).mockResolvedValue(transferResultMock());
    const wrapper = mount(BedMapView);
    await flushPromises();
    // 占床卡选择「转科转床」打开弹窗
    const occupiedCard = findBedCard(wrapper, '03');
    await occupiedCard?.find('select').setValue('transferDialog');
    await flushPromises();
    // 切转科模式 → 目标病区空床清单加载 → 选目标床位 → 提交
    await wrapper.find('input[value="transfer"]').setValue();
    await flushPromises();
    await wrapper.find('select[aria-label="目标床位"]').setValue('902');
    await clickButton(wrapper, '确认转科');
    await flushPromises();
    expect(transfer.execute).toHaveBeenCalledWith('I2026092000003', {
      toWardId: 'W01',
      toBedId: '902',
    });
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toContain('已转科完成');
    // 床位图重载（首拉 + 目标床候选 + 提交后重载）
    expect(beds.map).toHaveBeenCalledTimes(3);
    wrapper.unmount();
  });

  it('同病区转床轻量路径出网（transfer.changeBed），成功盖钤印「已转床」', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    vi.mocked(transfer.changeBed).mockResolvedValue(transferResultMock());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const occupiedCard = findBedCard(wrapper, '03');
    await occupiedCard?.find('select').setValue('transferDialog');
    await flushPromises();
    // 默认 change 模式（同病区轻量转床）：只选目标床位，不携目标病区
    await wrapper.find('select[aria-label="目标床位"]').setValue('902');
    await clickButton(wrapper, '确认转床');
    await flushPromises();
    expect(transfer.changeBed).toHaveBeenCalledWith('I2026092000003', { toBedId: '902' });
    expect(transfer.execute).not.toHaveBeenCalled();
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toContain('已转床');
    expect(beds.map).toHaveBeenCalledTimes(3);
    wrapper.unmount();
  });

  it('转科未选目标床位零出网显式校验拦截', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mount(BedMapView);
    await flushPromises();
    const occupiedCard = findBedCard(wrapper, '03');
    await occupiedCard?.find('select').setValue('transferDialog');
    await flushPromises();
    await wrapper.find('input[value="transfer"]').setValue();
    await flushPromises();
    await clickButton(wrapper, '确认转科');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请选择目标床位');
    expect(transfer.execute).not.toHaveBeenCalled();
    // 拦截路径不盖钤印
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(BedMapView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 inpatient:bed:btn:change 时占床卡不出现转科转床动作（D-34 无码全隐藏）', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 1 } }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('I2026092000003');
    expect(wrapper.text()).not.toContain('转科转床');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 inpatient:bed:btn:change 时占床卡出现转科转床动作', async () => {
    vi.mocked(beds.map).mockResolvedValue(fiveStateBeds());
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('转科转床');
    wrapper.unmount();
  });

  it('卡墙分组折叠（G5 ⑨.4）：>120 床按床号十位段分组签行，组头折叠/展开切换', async () => {
    vi.mocked(beds.map).mockResolvedValue(bulkBeds(130));
    const wrapper = mount(BedMapView);
    await flushPromises();
    // 130 床 → 13 个十位段组头（「01-10 床」…「121-130 床」）
    const heads = wrapper.findAll('.bed-wall-group-head');
    expect(heads).toHaveLength(13);
    expect(heads[0]?.text()).toContain('01-10 床');
    expect(heads[12]?.text()).toContain('121-130 床');
    expect(wrapper.findAll('.bed-card')).toHaveLength(130);
    // 折叠首组：组内 10 卡隐藏（aria-expanded 同步翻转），其余 120 卡仍在
    await heads[0]?.trigger('click');
    const collapsedHead = wrapper.findAll('.bed-wall-group-head')[0];
    expect(collapsedHead?.attributes('aria-expanded')).toBe('false');
    expect(wrapper.findAll('.bed-card')).toHaveLength(120);
    // 展开恢复
    await collapsedHead?.trigger('click');
    expect(wrapper.findAll('.bed-wall-group-head')[0]?.attributes('aria-expanded')).toBe('true');
    expect(wrapper.findAll('.bed-card')).toHaveLength(130);
    // 图例计数不随折叠失真（同源计数=全量 bedList 派生）
    expect(wrapper.find('.bed-map-legend--free .fuy-num').text()).toBe('130');
    wrapper.unmount();
  });
});
