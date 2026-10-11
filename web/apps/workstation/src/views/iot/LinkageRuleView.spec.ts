// 联动规则页单测（/iot/linkage-rules，M16 联动规则 CRUD + 执行日志重试面前端面 · 暖纸卷宗
// 换脸重排 丙-6）：「换脸不换业务」——规则 CRUD 状态机/触发条件与动作配置 JSON 显式校验
// （禁裸 parse 非法零出网）/弹窗未保存草稿守卫（before-close 全通道）/删除与重试行级在途
// 守卫/重试链零变动；spec 按丙-6 蓝图锚点重写（蓝图丙-6.7 ①~⑤）。
// 断言现代化留痕（本次改动直接波及项，按新契约重写非放宽）：
// ① FAILED 行=左缘印泥朱 3px 规线+行级「重试」link（蓝图丙-6.2 PM 重排裁决：失败行行级
//    视觉与动作双通道）：row-class-name 契约类 fuy-linkage-row--failed 断言（FAILED 行独占，
//    成功/待执行行零规线类）——锚点①；
// ② FAILED 计数胶囊（蓝图丙-6.2：前端自 logRows 过滤 FAILED 派生零出网）：初载骨架态
//    （禁伪 0 值）→ 内容态恒显「FAILED N」印泥朱胶囊（含 0），重试重拉后计数随新行集派生
//    更新——锚点②；
// ③ 草稿守卫文案冻结：ElMessageBox.confirm 消息/标题/按钮三段逐字精确匹配（原
//    stringContaining 弱匹配按新契约升格为全量精确匹配，严格度只增不减）——锚点③；
// ④ WARD_OPTIONS（自 @/api/ward 跨域假常量 '1001'）→ listOrgs({type:'WARD'}) 真数据三态
//    （册级总则 G4/蓝图丙-6.5）：装配即拉/在途禁用/到达出选项（默认保持「全院」空值=目标
//    病区可空业务语义零变动，禁自动落首项）/失败说问题给恢复+全院规则仍可保存；源码级反向
//    断言=本页零 @/api/ward import、零 WARD_OPTIONS 残留（锚点④ ward.ts 跨域消费点清零，
//    本页源码级收口；ward.ts 常量本体留归病区域批次删除）；
// ⑤ 钤印族（蓝图丙-6.4）：规则保存=钤印「已保存」（新建/编辑同族）、重试成功=钤印
//    「已重试」；校验拦截与失败=message 批注条零钤印（钤印永不承失败反向断言）——原
//    ElMessage.success 断言按新契约重写并留痕（删除成功反馈仍归 message，蓝图未裁改）；
// 另：门牌页首三锚（衬线标题「联动规则」+英文小注 LINKAGE+批注行）+页面根节点禁挂
//     .fuy-stagger（契约 ⑦.4，flow 包裹承接 flex+gap）+规则弹窗 640px 挂 .fuy-dialog/表单
//     容器挂 .fuy-form（蓝图丙-6.3）+空态脸（契约 ⑥）。
// api mock 承载零出网（vi.mock('@/api/iot') + vi.mock('@/api/system') 整模块替身），断言
// 业务结果不绑定实现细节。
// 桩面：删除/草稿守卫确认走 ElMessageBox.confirm 替身（默认确认放行，既有删除/守卫链业务
// 断言零改动）。
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { linkageLogs, linkageRules } from '@/api/iot';
import type { LinkageLogVO, LinkageRuleVO } from '@/api/iot';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import LinkageRuleView from './LinkageRuleView.vue';

vi.mock('@/api/iot', () => ({
  TRIGGER_SOURCE_LABELS: {
    ALARM_TRIGGERED: '告警触发',
    TELEMETRY_ANOMALY: '遥测异常',
    DEVICE_STATUS: '设备状态',
  },
  ACTION_TYPE_LABELS: {
    NOTIFY: '站内通知',
    M01_NOTIFY: 'M01 通知',
    CALL_TRANSFER: '呼叫转接',
    NURSING_TASK: '护理任务',
    WARD_BROADCAST: '病区播报',
  },
  LINKAGE_RESULT_LABELS: { SUCCESS: '成功', FAILED: '失败', PENDING: '待执行' },
  linkageRules: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  linkageLogs: { page: vi.fn(), retry: vi.fn() },
}));

// 病区清单真数据源替身（G4：ward.ts WARD_OPTIONS 跨域假常量清零，目标病区下拉走 listOrgs
// WARD 档；本 spec 不再替身 @/api/ward——组件侧 import 清零由锚点④源码级反向断言收口）
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox（提示与删除/守卫确认断言用），其余导出原样保留
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: { ...mod.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') },
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

/** 规则行（可覆写词表值/启用态） */
function ruleMock(partial: Partial<LinkageRuleVO> = {}): LinkageRuleVO {
  return {
    id: '701',
    ruleName: '危急告警转呼叫',
    triggerSource: 'ALARM_TRIGGERED',
    triggerCondition: { level: 'CRITICAL' },
    actionType: 'CALL_TRANSFER',
    actionConfig: { priority: 'high' },
    targetWardId: 'W01',
    enabled: true,
    createdAt: '2026-09-25T10:00:00+08:00',
    updatedAt: '2026-09-25T10:00:00+08:00',
    ...partial,
  };
}

/** 执行日志行（可覆写结果态） */
function logMock(partial: Partial<LinkageLogVO> = {}): LinkageLogVO {
  return {
    id: '601',
    linkageNo: 'LK20260926001',
    ruleId: '701',
    triggerSource: 'ALARM_TRIGGERED',
    triggerRef: 'AL20260926001',
    actionType: 'CALL_TRANSFER',
    actionResult: 'FAILED',
    retryCount: 1,
    errorMsg: '目标病区路由缺失',
    executedAt: '2026-09-26T10:00:00+08:00',
    createdAt: '2026-09-26T10:00:00+08:00',
    ...partial,
  };
}

/** 空日志分页出参 */
function emptyLogPage() {
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

describe('联动规则页', () => {
  /** 文件级 Pinia：v-perm 指令与门牌批注行签认人读取 auth 会话 store，每用例新实例防串扰 */
  let pinia: Pinia;

  /** 统一挂载（门牌批注行签认人消费 auth store + v-perm 元素码判定，均需 pinia 在位；
   *  perm 指令显式注册=main.ts 全局注册仅应用装配态，单测自备） */
  function mountView(): VueWrapper {
    return mount(LinkageRuleView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #36）：真实 IOT_ADMIN 会话经登录契约导出含码含名，既有用例语义不变
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
          permissions: ['iot:linkage:btn:manage'],
        },
      }),
    );
    for (const fn of [
      linkageRules.list,
      linkageRules.create,
      linkageRules.update,
      linkageRules.remove,
      linkageLogs.page,
      linkageLogs.retry,
      listOrgs,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 确认框替身调用记录清零（实现默认确认放行，单用例按需 mockRejectedValueOnce 覆写）
    vi.mocked(ElMessageBox.confirm).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链；病区清单真数据源默认落种子（G4 真数据三态可复现）
    vi.mocked(linkageRules.list).mockResolvedValue([]);
    vi.mocked(linkageLogs.page).mockResolvedValue(emptyLogPage());
    vi.mocked(listOrgs).mockResolvedValue(WARD_SEED);
  });

  it('门牌页首锚点：衬线标题「联动规则」+ 英文小注 LINKAGE + 批注行 + FAILED 胶囊/刷新状态位 + 页面根不挂 stagger（契约 ⑦.4）', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 门牌三锚（蓝图丙-6.2/契约 ⑧.1）：衬线标题 + 装饰英文小注 + 批注行（hint 升批注带「谁·时刻」）
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('联动规则');
    expect(wrapper.find('.fuy-page-en').text()).toBe('LINKAGE');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('触发源 × 动作类型治理 · 命中执行归联动引擎 · 失败可重试');
    expect(note.text()).toContain('签认人 陆物联');
    // 状态位=FAILED 计数胶囊+刷新（蓝图丙-6.2：胶囊派生断言详锚点②专测，此处验位）
    expect(wrapper.find('.fuy-page-status .fuy-status-pill--danger').exists()).toBe(true);
    expect(wrapper.findAll('button').some((b) => b.text() === '刷新')).toBe(true);
    // 契约 ⑦.4：页面根节点禁挂 .fuy-stagger（flow 包裹 div 承接 flex+gap）
    const root = wrapper.find('.fuy-page.linkage-rule');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(root.find('.fuy-stagger.linkage-flow').exists()).toBe(true);
    // 页脚批注（G1 纵向序收尾，契约 ⑧.8：数据时点/刷新语义，禁放操作钮）
    expect(wrapper.find('.fuy-page-foot').exists()).toBe(true);
    wrapper.unmount();
  });

  it('锚点②FAILED 计数胶囊：初载骨架态→内容态，自 logRows 过滤 FAILED 派生零出网', async () => {
    vi.mocked(linkageLogs.page).mockResolvedValue({
      content: [
        logMock({ actionResult: 'FAILED' }),
        logMock({ id: '602', linkageNo: 'LK20260926002', actionResult: 'FAILED' }),
        logMock({ id: '603', linkageNo: 'LK20260926003', actionResult: 'SUCCESS' }),
        logMock({ id: '604', linkageNo: 'LK20260926004', actionResult: 'PENDING' }),
      ],
      page: '0',
      size: '20',
      total: '4',
    });
    const wrapper = mountView();
    // 挂载即骨架态（首拉未落地，禁伪 0 值）：静态金洗纸占位 + 零内容胶囊
    expect(wrapper.find('.fuy-page-status .linkage-failed-sk').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-status .fuy-status-pill').exists()).toBe(false);
    await flushPromises();
    // 内容态：印泥朱胶囊（danger 变体）+ FAILED 过滤派生计数（4 行中 2 失败）
    const pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.exists()).toBe(true);
    expect(pill.classes()).toContain('fuy-status-pill--danger');
    expect(pill.text()).toBe('FAILED 2');
    // 派生零出网：计数自已加载行集过滤，日志出网仍只 mount 时一次
    expect(linkageLogs.page).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('锚点②FAILED 计数派生更新：重试重拉后全成功行集派生「FAILED 0」（零值诚实展示）', async () => {
    vi.mocked(linkageLogs.page)
      .mockResolvedValueOnce({
        content: [logMock({ actionResult: 'FAILED' })],
        page: '0',
        size: '20',
        total: '1',
      })
      .mockResolvedValue({
        content: [logMock({ actionResult: 'SUCCESS' })],
        page: '0',
        size: '20',
        total: '1',
      });
    vi.mocked(linkageLogs.retry).mockResolvedValue(logMock({ actionResult: 'SUCCESS' }));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('.fuy-page-status .fuy-status-pill').text()).toBe('FAILED 1');
    await clickRowButton(wrapper, 'LK20260926001', '重试');
    await flushPromises();
    // 重拉后计数随新行集派生更新（含 0）：重试成功即失败量归零，免扫表免出网
    expect(wrapper.find('.fuy-page-status .fuy-status-pill').text()).toBe('FAILED 0');
    expect(linkageLogs.page).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('锚点①FAILED 行规线类：row-class-name 契约类 FAILED 行独占，成功/待执行行零规线类', async () => {
    vi.mocked(linkageLogs.page).mockResolvedValue({
      content: [
        logMock({ actionResult: 'FAILED' }),
        logMock({ id: '602', linkageNo: 'LK20260926002', actionResult: 'SUCCESS' }),
        logMock({ id: '603', linkageNo: 'LK20260926003', actionResult: 'PENDING' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mountView();
    await flushPromises();
    // 规线类只落在 FAILED 行（蓝图丙-6.2：失败行行级视觉通道；与「重试」link 动作通道双轨）
    const failedRows = wrapper.findAll('tr.fuy-linkage-row--failed');
    expect(failedRows.length).toBe(1);
    expect(failedRows[0].text()).toContain('LK20260926001');
    // 成功/待执行行零规线类（失败行视觉零差异问题的机器判据）
    expect(findRow(wrapper, 'LK20260926002')?.classes()).not.toContain('fuy-linkage-row--failed');
    expect(findRow(wrapper, 'LK20260926003')?.classes()).not.toContain('fuy-linkage-row--failed');
    // 行级动作通道：重试 link 仅 FAILED 行暴露（既有状态机零变动）
    expect(
      findRow(wrapper, 'LK20260926001')
        ?.findAll('button')
        .some((b) => b.text() === '重试'),
    ).toBe(true);
    expect(
      findRow(wrapper, 'LK20260926002')
        ?.findAll('button')
        .some((b) => b.text() === '重试'),
    ).toBe(false);
    wrapper.unmount();
  });

  it('规则列表加载渲染触发源/动作类型词表徽标与启用态（机器判据）', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([
      ruleMock({ id: '701', enabled: true }),
      ruleMock({
        id: '702',
        ruleName: '遥测异常护理任务',
        triggerSource: 'TELEMETRY_ANOMALY',
        actionType: 'NURSING_TASK',
        enabled: false,
      }),
    ]);
    const wrapper = mountView();
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('危急告警转呼叫');
    expect(text).toContain('告警触发');
    expect(text).toContain('呼叫转接');
    expect(text).toContain('遥测异常');
    expect(text).toContain('护理任务');
    // 触发源徽标状态类契约（机器判据；色值经语义 token 承载）
    expect(wrapper.find('.fuy-rule-tag--alarm_triggered').exists()).toBe(true);
    expect(wrapper.find('.fuy-rule-tag--telemetry_anomaly').exists()).toBe(true);
    wrapper.unmount();
  });

  it('空态脸（契约 ⑥）：规则/日志双空态脸（暂无业务客体主句 + 说明给下一步 + 零默认插画）', async () => {
    const wrapper = mountView();
    await flushPromises();
    const empties = wrapper.findAll('.fuy-empty');
    expect(empties.length).toBe(2);
    expect(empties[0].find('.fuy-empty-title').text()).toBe('暂无联动规则');
    expect(empties[0].find('.fuy-empty-hint').text().length).toBeGreaterThan(0);
    expect(empties[1].find('.fuy-empty-title').text()).toBe('暂无执行日志');
    expect(empties[1].find('.fuy-empty-hint').text().length).toBeGreaterThan(0);
    // 默认纸箱插画在 fuy-page 子树全站压制（契约 ⑥ 挂类前缀组合由全局段承载，验零渲染兜底）
    expect(wrapper.find('.fuy-empty img').exists()).toBe(false);
    wrapper.unmount();
  });

  it('锚点④病区清单真数据三态（装载）：listOrgs({type:"WARD"}) 装配即拉，在途禁用，到达出选项且默认保持「全院」空值', async () => {
    let releaseWards: () => void = () => {};
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          releaseWards = () => resolve(WARD_SEED);
        }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 页面装配即拉真数据病区清单（ward.ts WARD_OPTIONS 跨域假常量清零后唯一真数据源，G4）
    expect(vi.mocked(listOrgs)).toHaveBeenCalledWith({ type: 'WARD' });
    await clickButton(wrapper, '新建规则');
    // 在途：目标病区下拉禁用（清单未就绪不可选）
    const wardSelect = wrapper.find('select[aria-label="目标病区"]');
    expect(wardSelect.attributes('disabled')).toBeDefined();
    // 清单到达：解禁、选项=「全院」空值+「名称（编码）」渲染口径；默认保持「全院」空值
    // （目标病区可空=全院为既有业务语义，禁自动落首项——与绑定表单强制病区语义分叉）
    releaseWards();
    await flushPromises();
    expect(wardSelect.attributes('disabled')).toBeUndefined();
    expect(wardSelect.findAll('option').map((option) => option.text())).toEqual([
      '全院',
      '演示病区（W01）',
    ]);
    expect((wardSelect.element as HTMLSelectElement).value).toBe('');
    wrapper.unmount();
  });

  it('锚点④病区清单真数据三态（失败恢复）：说问题给恢复，「全院」空值仍可选，重试后选项到达', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('网络不可用'));
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    // 失败态=说问题给恢复（G4/G6：不伪装空选项）；目标病区可空业务语义下下拉不禁用
    expect(wrapper.text()).toContain('病区清单加载失败');
    const wardSelect = wrapper.find('select[aria-label="目标病区"]');
    expect(wardSelect.attributes('disabled')).toBeUndefined();
    // 重试恢复：清单到达后失败条退场、真数据选项出现
    await clickButton(wrapper, '重试');
    await flushPromises();
    expect(wrapper.text()).not.toContain('病区清单加载失败');
    expect(wardSelect.findAll('option').map((option) => option.text())).toEqual([
      '全院',
      '演示病区（W01）',
    ]);
    wrapper.unmount();
  });

  it('锚点④失败态不阻断全院规则写入：目标病区可空=全院为合法业务态，保存零病区出网拦截', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('网络不可用'));
    vi.mocked(linkageRules.create).mockResolvedValue(ruleMock());
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    expect(wrapper.text()).toContain('病区清单加载失败');
    // 失败态保存：不选病区（保持「全院」空值）直接保存——出网载荷 targetWardId=undefined
    await wrapper.find('input[aria-label="规则名称"]').setValue('全院停用播报');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(linkageRules.create).toHaveBeenCalledWith(
      expect.objectContaining({ ruleName: '全院停用播报', targetWardId: undefined }),
    );
    wrapper.unmount();
  });

  it('规则新建触发条件 JSON 非法零出网显式校验拦截（文案冻结）+ 校验拦截零钤印反向断言', async () => {
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    await wrapper.find('input[aria-label="规则名称"]').setValue('新规则');
    await wrapper.find('textarea[aria-label="触发条件 JSON"]').setValue('{level:');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '触发条件须为合法 JSON 对象，请修正后再试',
    );
    expect(linkageRules.create).not.toHaveBeenCalled();
    // 校验拦截=业务拒绝通道：零钤印（钤印只承成功确认，锚点⑤反向断言）
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    wrapper.unmount();
  });

  it('锚点⑤规则新建合法出网携词表值与目标病区真编码并刷新，成功反馈=钤印「已保存」', async () => {
    vi.mocked(linkageRules.create).mockResolvedValue(ruleMock());
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    await wrapper.find('input[aria-label="规则名称"]').setValue('危急告警播报');
    await wrapper.find('select[aria-label="触发源"]').setValue('DEVICE_STATUS');
    await wrapper.find('textarea[aria-label="触发条件 JSON"]').setValue('{"status":"OFFLINE"}');
    await wrapper.find('select[aria-label="动作类型"]').setValue('WARD_BROADCAST');
    await wrapper.find('select[aria-label="目标病区"]').setValue('W01');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(linkageRules.create).toHaveBeenCalledWith({
      ruleName: '危急告警播报',
      triggerSource: 'DEVICE_STATUS',
      triggerCondition: { status: 'OFFLINE' },
      actionType: 'WARD_BROADCAST',
      actionConfig: undefined,
      targetWardId: 'W01',
      enabled: true,
    });
    // 成功反馈=钤印（蓝图丙-6.4，留痕见文件头⑤）：文案=动词结果「已保存」，不再走 ElMessage
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已保存');
    expect(stamp.attributes('aria-live')).toBe('polite');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(linkageRules.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('规则编辑预填回显原值并出网整单替换（update），成功反馈=钤印「已保存」', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(linkageRules.update).mockResolvedValue(ruleMock());
    const wrapper = mountView();
    await flushPromises();
    await clickRowButton(wrapper, '危急告警转呼叫', '编辑');
    // 预填回显（编辑框承载原规则值）
    const nameInput = wrapper.find('input[aria-label="规则名称"]').element as HTMLInputElement;
    expect(nameInput.value).toBe('危急告警转呼叫');
    await wrapper.find('input[aria-label="规则名称"]').setValue('危急告警转护理任务');
    await wrapper.find('select[aria-label="动作类型"]').setValue('NURSING_TASK');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(linkageRules.update).toHaveBeenCalledWith('701', {
      ruleName: '危急告警转护理任务',
      triggerSource: 'ALARM_TRIGGERED',
      triggerCondition: { level: 'CRITICAL' },
      actionType: 'NURSING_TASK',
      actionConfig: { priority: 'high' },
      targetWardId: 'W01',
      enabled: true,
    });
    // 编辑分支同族钤印（蓝图丙-6.4：保存=钤印「已保存」，新建/编辑同族）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已保存');
    expect(linkageRules.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('规则删除出网并刷新列表（删除成功反馈仍=message，蓝图丙-6.4 未裁改）', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    const wrapper = mountView();
    await flushPromises();
    await clickRowButton(wrapper, '危急告警转呼叫', '删除');
    await flushPromises();
    expect(linkageRules.remove).toHaveBeenCalledWith('701');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(linkageRules.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('执行日志渲染结果三态徽标，FAILED 行重试出网携 linkageNo 并刷新，成功反馈=钤印「已重试」（锚点⑤）', async () => {
    vi.mocked(linkageLogs.page).mockResolvedValue({
      content: [
        logMock({ actionResult: 'FAILED' }),
        logMock({ linkageNo: 'LK20260926002', actionResult: 'SUCCESS' }),
        logMock({ linkageNo: 'LK20260926003', actionResult: 'PENDING' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    vi.mocked(linkageLogs.retry).mockResolvedValue(logMock({ actionResult: 'SUCCESS' }));
    const wrapper = mountView();
    await flushPromises();
    // 结果三态徽标状态类契约（机器判据）
    expect(wrapper.find('.fuy-linkage-tag--failed').exists()).toBe(true);
    expect(wrapper.find('.fuy-linkage-tag--success').exists()).toBe(true);
    expect(wrapper.find('.fuy-linkage-tag--pending').exists()).toBe(true);
    await clickRowButton(wrapper, 'LK20260926001', '重试');
    await flushPromises();
    expect(linkageLogs.retry).toHaveBeenCalledWith('LK20260926001');
    // 成功反馈=钤印「已重试」（蓝图丙-6.4：重试成功=钤印，失败=message 分叉）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已重试');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 日志重载（page 第二次调用）
    expect(linkageLogs.page).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('删除在途守卫：双击窗口内重复点击仅一次出网（防重复提交删除）', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(linkageRules.remove).mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();
    const deleteButton = findRow(wrapper, '危急告警转呼叫')
      ?.findAll('button')
      .find((b) => b.text() === '删除');
    expect(deleteButton).toBeDefined();
    // 双击（第二次点击落在首次出网在途窗口内，未 flush）
    await deleteButton?.trigger('click');
    await deleteButton?.trigger('click');
    await flushPromises();
    expect(linkageRules.remove).toHaveBeenCalledTimes(1);
    expect(linkageRules.remove).toHaveBeenCalledWith('701');
    wrapper.unmount();
  });

  it('重试在途守卫：双击窗口内重复点击仅一次出网（防重复投递联动动作与 retryCount 双计）', async () => {
    vi.mocked(linkageLogs.page).mockResolvedValue({
      content: [logMock({ actionResult: 'FAILED' })],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(linkageLogs.retry).mockResolvedValue(logMock({ actionResult: 'SUCCESS' }));
    const wrapper = mountView();
    await flushPromises();
    const retryButton = findRow(wrapper, 'LK20260926001')
      ?.findAll('button')
      .find((b) => b.text() === '重试');
    expect(retryButton).toBeDefined();
    // 双击（第二次点击落在首次重投在途窗口内，未 flush）
    await retryButton?.trigger('click');
    await retryButton?.trigger('click');
    await flushPromises();
    expect(linkageLogs.retry).toHaveBeenCalledTimes(1);
    expect(linkageLogs.retry).toHaveBeenCalledWith('LK20260926001');
    wrapper.unmount();
  });

  it('规则删除前确认：取消零出网，确认后出网并刷新（EX-46/FE-A2-05）', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(linkageRules.remove).mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();
    // 取消：确认框带规则名回显摘要，零出网
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await clickRowButton(wrapper, '危急告警转呼叫', '删除');
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('危急告警转呼叫'),
      '规则删除确认',
      expect.anything(),
    );
    expect(linkageRules.remove).not.toHaveBeenCalled();
    // 确认：出网删除并刷新列表（取消后守卫复位可再次发起）
    await clickRowButton(wrapper, '危急告警转呼叫', '删除');
    await flushPromises();
    expect(linkageRules.remove).toHaveBeenCalledWith('701');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(linkageRules.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('锚点③草稿守卫文案冻结：脏表单取消/X 关闭需确认丢弃（三段文案逐字），拒绝则弹窗驻留（EX-46/FE-A2-06）', async () => {
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    await wrapper.find('input[aria-label="规则名称"]').setValue('新规则');
    // 取消按钮关闭：脏表单先确认丢弃；消息/标题/按钮三段文案逐字冻结（原 stringContaining
    // 弱匹配按新契约升格为全量精确匹配，严格度只增不减）
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await clickButton(wrapper, '取消');
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      '规则表单已有未保存修改，关闭将丢弃这些内容',
      '未保存提醒',
      expect.objectContaining({ confirmButtonText: '丢弃并关闭', cancelButtonText: '继续编辑' }),
    );
    expect(wrapper.find('input[aria-label="规则名称"]').isVisible()).toBe(true);
    // 右上角 X 关闭走同款守卫：拒绝丢弃 → 弹窗驻留（before-close 全通道）
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await wrapper.find('.el-dialog__headerbtn').trigger('click');
    await flushPromises();
    expect(wrapper.find('input[aria-label="规则名称"]').isVisible()).toBe(true);
    // 再次取消并确认丢弃 → 弹窗关闭
    await clickButton(wrapper, '取消');
    await flushPromises();
    expect(wrapper.find('input[aria-label="规则名称"]').isVisible()).toBe(false);
    wrapper.unmount();
  });

  it('规则弹窗无改动时取消关闭零确认（EX-46/FE-A2-06）', async () => {
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    // 未做任何录入：直接关闭零打扰（不弹未保存确认）
    await clickButton(wrapper, '取消');
    await flushPromises();
    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    expect(wrapper.find('input[aria-label="规则名称"]').isVisible()).toBe(false);
    wrapper.unmount();
  });

  it('规则弹窗挂类锚点（蓝图丙-6.3）：640px 挂 .fuy-dialog + 表单容器挂 .fuy-form', async () => {
    const wrapper = mountView();
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    await flushPromises();
    // 规则弹窗挂 .fuy-dialog（G2：衬线标题 + 头部发丝由全局段承载）+ 640px 宽档
    const dialog = wrapper.find('.el-dialog.fuy-dialog');
    expect(dialog.exists()).toBe(true);
    expect(dialog.attributes('style')).toContain('640px');
    // 表单容器挂 .fuy-form（蓝图丙-6.3：文书脸由全局表单段承载）
    expect(dialog.find('.fuy-form').exists()).toBe(true);
    wrapper.unmount();
  });

  it('锚点④源码级反向断言：本页零 @/api/ward import、零 WARD_OPTIONS import 残留，listOrgs WARD 档在位', () => {
    // ward.ts WARD_OPTIONS（'1001'）为跨域假常量，本页是其消费点——换源后本页 import 级
    // 清零（锚点④收口；常量本体留归病区域批次删除，本断言防回流。断言匹配 import 语句
    // 形态而非全文出现——文件头 G4 收编注记提及常量名属文档非消费，不计入）
    const source = readFileSync(join(import.meta.dirname, 'LinkageRuleView.vue'), 'utf-8');
    expect(source).not.toMatch(/import\s*\{[^}]*\}\s*from\s*'@\/api\/ward'/);
    expect(source).not.toMatch(/import\s*\{[^}]*WARD_OPTIONS/);
    expect(source).toContain("listOrgs({ type: 'WARD' })");
  });

  it('PR-4F 权限态：会话无 iot:linkage:btn:manage 时新建/编辑/删除/重试全隐藏（D-34）', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(linkageLogs.page).mockResolvedValue({
      content: [logMock({ actionResult: 'FAILED' })],
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
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // 规则新建/编辑/删除与失败重试四个写口无码全隐藏
    expect(buttonTexts).not.toContain('新建规则');
    expect(buttonTexts).not.toContain('编辑');
    expect(buttonTexts).not.toContain('删除');
    expect(buttonTexts).not.toContain('重试');
    // 规则/日志读面不受元素码影响
    expect(wrapper.text()).toContain('危急告警转呼叫');
    expect(wrapper.text()).toContain('LK20260926001');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 iot:linkage:btn:manage 时规则写面与失败重试可见', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(linkageLogs.page).mockResolvedValue({
      content: [logMock({ actionResult: 'FAILED' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('新建规则');
    expect(buttonTexts).toContain('编辑');
    expect(buttonTexts).toContain('删除');
    expect(buttonTexts).toContain('重试');
    wrapper.unmount();
  });
});
