// 卡墙底座单测（EX-47 拆分）：病区主加载富化与联动重载、卡墙选中回调时序（复位先于加载）、
// 病区切换清选中并写会话记忆。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SHIFT_OPTIONS, WARD_OPTIONS, wardPatients } from '@/api/nursing';
import type { WardPatientDetailVO, WardPatientVO } from '@/api/nursing';
import { useWardContext } from './useWardContext';

vi.mock('@/api/nursing', () => ({
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  SHIFT_OPTIONS: [{ code: 'DAY', label: '白班' }],
  wardPatients: { register: vi.fn(), list: vi.fn(), detail: vi.fn(), remove: vi.fn() },
}));

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

describe('useWardContext', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.mocked(wardPatients.list).mockReset().mockResolvedValue([]);
    vi.mocked(wardPatients.detail).mockReset().mockResolvedValue(detailMock());
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
          conditionTags: visitId === 'I20260923000000001' ? 'CRITICAL' : '',
        }),
      ),
    );
    const reloads = vi.fn(() => [Promise.resolve(), Promise.resolve()]);
    const ctx = useWardContext({ getWardReloads: reloads });
    await ctx.loadWard();
    // 床位序（spec 冻结语序断言口径）+ 详情富化 + 病情计数
    expect(ctx.sortedPatients.value.map((patient) => patient.bedNo)).toEqual(['01', '03']);
    expect(ctx.detailMap.value['I20260923000000001']?.patientName).toBe('张三');
    expect(ctx.wardCounts.value).toEqual({ total: 2, critical: 1, severe: 0 });
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

  it('病区切换写会话记忆并清选中上下文', async () => {
    const ctx = useWardContext();
    ctx.selectedVisitId.value = 'I20260923000000001';
    ctx.wardId.value = 'W02';
    ctx.onWardChange();
    await vi.waitFor(() => expect(wardPatients.list).toHaveBeenCalledWith('W02'));
    expect(sessionStorage.getItem('nursing.wardId')).toBe('W02');
    expect(ctx.selectedVisitId.value).toBeNull();
    expect(WARD_OPTIONS[0]?.code).toBe('W01');
    expect(SHIFT_OPTIONS[0]?.code).toBe('DAY');
  });
});
