// 卡墙底座单测（EX-47 拆分 · 暖纸切片）：病区主加载富化与联动重载、卡墙选中回调时序
// （复位先于加载）、病区切换清选中并写会话记忆；新增病区清单真数据面用例——listOrgs
// ({type:'WARD'}) 请求参数、会话记忆病区在/不在清单的初值裁决、失败恢复态、空清单、
// 病区未就绪主加载零出网守卫、数据时点回填。既有业务断言全量保留；WARD_OPTIONS 假常量
// 消费随暖纸切片清零（对 api 常量的直接断言随之退役）。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SHIFT_OPTIONS, wardPatients } from '@/api/nursing';
import type { WardPatientDetailVO, WardPatientVO } from '@/api/nursing';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useWardContext } from './useWardContext';

vi.mock('@/api/nursing', () => ({
  SHIFT_OPTIONS: [{ code: 'DAY', label: '白班' }],
  wardPatients: { list: vi.fn(), detail: vi.fn() },
}));

vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
}));

/** 病区种子（V1123__seed_sys_org 镜像：WARD 两病区 ACTIVE，sort 升序） */
const WARD_SEED: OrgVO[] = [
  { id: '1123000000000000001', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD', sort: 1 },
  { id: '1123000000000000002', orgCode: '1001', orgName: '演示病区', orgType: 'WARD', sort: 2 },
];

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

/** 患者详情（角标计数可覆写） */
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

describe('useWardContext', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.mocked(wardPatients.list).mockReset().mockResolvedValue([]);
    vi.mocked(wardPatients.detail).mockReset().mockResolvedValue(detailMock());
    vi.mocked(listOrgs).mockReset().mockResolvedValue(WARD_SEED);
  });

  it('病区主加载富化详情索引并触发联动重载（一览→逐床→四面）', async () => {
    vi.mocked(wardPatients.list).mockResolvedValue([
      patientMock({ bedNo: '03', visitId: 'I20260923000000003' }),
      patientMock({ bedNo: '01', visitId: 'I20260923000000001' }),
    ]);
    vi.mocked(wardPatients.detail).mockImplementation((visitId) =>
      Promise.resolve(
        detailMock({
          visitId,
          patientName: visitId === 'I20260923000000001' ? '张三' : '李四',
        }),
      ),
    );
    const reloads = vi.fn(() => [Promise.resolve(), Promise.resolve()]);
    const ctx = useWardContext({ getWardReloads: reloads });
    // 病区未就绪守卫（暖纸切片新增）：清单未装配前 wardId 为空，主加载不出网
    await ctx.loadWard();
    expect(vi.mocked(wardPatients.list)).not.toHaveBeenCalled();
    // 清单装配落首项后主加载照常（床位序 + 详情富化 + 在区计数 + 联动重载）
    await ctx.loadWardOptions();
    expect(ctx.wardId.value).toBe('W01');
    await ctx.loadWard();
    expect(ctx.sortedPatients.value.map((patient) => patient.bedNo)).toEqual(['01', '03']);
    expect(ctx.detailMap.value['I20260923000000001']?.patientName).toBe('张三');
    expect(ctx.wardCounts.value).toEqual({ total: 2 });
    expect(reloads).toHaveBeenCalledTimes(1);
  });

  it('卡墙选中先换上下文锚点再复位草稿后加载上下文（时序冻结）', () => {
    const order: string[] = [];
    const ctx = useWardContext({
      onPatientSwitchReset: () => order.push('reset'),
      onPatientSwitchLoad: () => order.push('load'),
    });
    ctx.selectPatient(patientMock({ visitId: 'I20260923000000009' }));
    expect(ctx.selectedVisitId.value).toBe('I20260923000000009');
    expect(ctx.selectedDetail.value).toBeUndefined();
    // 草稿复位必须先于上下文加载发起（续提拦截依赖复位已生效）
    expect(order).toEqual(['reset', 'load']);
  });

  it('病区切换写会话记忆并清选中上下文（班次词表首项仍为白班）', async () => {
    const ctx = useWardContext();
    ctx.selectedVisitId.value = 'I20260923000000001';
    ctx.wardId.value = 'W02';
    ctx.onWardChange();
    await vi.waitFor(() => expect(wardPatients.list).toHaveBeenCalledWith('W02'));
    expect(sessionStorage.getItem('nursing.wardId')).toBe('W02');
    expect(ctx.selectedVisitId.value).toBeNull();
    // 班次词表为冻结契约词表（M05 契约非字典假常量，G4 不清）
    expect(SHIFT_OPTIONS[0]?.code).toBe('DAY');
  });

  it('病区清单真数据面：listOrgs({type:"WARD"}) 出网，空会话落首项且不写会话记忆', async () => {
    const ctx = useWardContext();
    const ready = await ctx.loadWardOptions();
    expect(vi.mocked(listOrgs)).toHaveBeenCalledWith({ type: 'WARD' });
    expect(ready).toBe(true);
    expect(ctx.wardOptions.value).toEqual([
      { code: 'W01', label: '演示病区（W01）' },
      { code: '1001', label: '演示病区（1001）' },
    ]);
    // 初值落首项；会话记忆仅随病区切换写入（loadWardOptions 装配不落记忆）
    expect(ctx.wardId.value).toBe('W01');
    expect(sessionStorage.getItem('nursing.wardId')).toBeNull();
    expect(ctx.wardOptionsLoading.value).toBe(false);
    expect(ctx.wardOptionsError.value).toBe(false);
  });

  it('病区清单真数据面：会话记忆病区仍在清单内则沿用（切换/刷新不回默认病区）', async () => {
    sessionStorage.setItem('nursing.wardId', '1001');
    const ctx = useWardContext();
    await ctx.loadWardOptions();
    expect(ctx.wardId.value).toBe('1001');
  });

  it('病区清单真数据面：会话记忆病区已不在清单内则回落首项（下线病区不驻留）', async () => {
    sessionStorage.setItem('nursing.wardId', 'W99');
    const ctx = useWardContext();
    await ctx.loadWardOptions();
    expect(ctx.wardId.value).toBe('W01');
  });

  it('病区清单真数据面：失败落恢复态返回 false（弹错归拦截器，视图给重新加载）', async () => {
    vi.mocked(listOrgs).mockRejectedValue(new Error('网络不可用'));
    const ctx = useWardContext();
    const ready = await ctx.loadWardOptions();
    expect(ready).toBe(false);
    expect(ctx.wardOptionsError.value).toBe(true);
    expect(ctx.wardOptions.value).toEqual([]);
    expect(ctx.wardId.value).toBe('');
    expect(ctx.wardOptionsLoading.value).toBe(false);
  });

  it('病区清单真数据面：空清单返回 false 且病区上下文驻留空（零病区面出网前提）', async () => {
    vi.mocked(listOrgs).mockResolvedValue([]);
    const ctx = useWardContext();
    const ready = await ctx.loadWardOptions();
    expect(ready).toBe(false);
    expect(ctx.wardOptions.value).toEqual([]);
    expect(ctx.wardId.value).toBe('');
  });

  it('病区面数据时点：主加载成功回填 loadedAt，失败驻留旧值', async () => {
    const ctx = useWardContext();
    await ctx.loadWardOptions();
    expect(ctx.loadedAt.value).toBeNull();
    vi.mocked(wardPatients.list).mockRejectedValue(new Error('网络不可用'));
    await ctx.loadWard();
    expect(ctx.loadedAt.value).toBeNull();
    vi.mocked(wardPatients.list).mockResolvedValue([patientMock()]);
    await ctx.loadWard();
    expect(ctx.loadedAt.value).not.toBeNull();
  });
});
