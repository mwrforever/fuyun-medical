// 号源查询与选择组合单测（EX-48 自 AppointmentView 下沉面）：正常查询（条件透传/列表落定）、
// 刷新清已选（防携带失效选择提交）、身份准入守卫零出网、在途防重入、OP-1007/网络异常错误
// 文案映射、余 0 拒选与正常落选、复位号源面。api mock 承载（与页面 spec 同桩面
// '@/api/outpatient'，resolveErrorCopy 保持真实实现），不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import type { Ref } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PortalApiError } from '@/api/http';
import { listPortalPools } from '@/api/outpatient';
import type { NumberPoolVO } from '@/api/outpatient';
import { useNumberPools } from './useNumberPools';

vi.mock('@/api/outpatient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/outpatient')>();
  return {
    ...actual,
    listPortalPools: vi.fn(),
  };
});

/** 消费组合实例 API 类型 */
type NumberPoolsApi = ReturnType<typeof useNumberPools>;

/** 号源池桩（id/余量参数化，字段与页面 spec 同形） */
function poolMock(id: string, remaining: number): NumberPoolVO {
  return {
    id,
    scheduleId: '301',
    apptType: 'GENERAL',
    slotStart: '08:00',
    slotEnd: '11:30',
    totalQuota: 20,
    usedCount: 20 - remaining,
    remaining,
  };
}

/**
 * 挂载消费组件：setup 内以默认注入面（诊区 DEPT-INT/日期 2026-09-28/准入恒过）调用
 * useNumberPools；canQuery 覆写身份守卫用例。回传条件 ref 供查询参数断言。
 */
function mountConsumer(canQuery: () => boolean = () => true): {
  unmount: () => void;
  api: NumberPoolsApi;
  deptCode: Ref<string>;
  schedDate: Ref<string>;
} {
  const deptCode = ref('DEPT-INT');
  const schedDate = ref('2026-09-28');
  let api!: NumberPoolsApi;
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useNumberPools({ deptCode, schedDate, canQuery });
        return () => h('div');
      },
    }),
  );
  return { unmount: () => wrapper.unmount(), api, deptCode, schedDate };
}

describe('号源查询与选择组合', () => {
  beforeEach(() => {
    vi.mocked(listPortalPools).mockReset();
  });

  it('正常查询：诊区/日期条件透传出网，列表落定且加载态复位', async () => {
    vi.mocked(listPortalPools).mockResolvedValue([poolMock('501', 6), poolMock('502', 3)]);
    const { api, unmount } = mountConsumer();

    await api.queryPools();
    await flushPromises();

    expect(vi.mocked(listPortalPools)).toHaveBeenCalledWith({
      deptCode: 'DEPT-INT',
      date: '2026-09-28',
    });
    expect(api.pools.value.length).toBe(2);
    expect(api.poolsLoading.value).toBe(false);
    expect(api.poolsError.value).toBe('');
    unmount();
  });

  it('刷新清已选：重查后已选号源清空（防携带失效选择提交）', async () => {
    const first = [poolMock('501', 6)];
    const second = [poolMock('501', 2), poolMock('502', 5)];
    vi.mocked(listPortalPools).mockResolvedValueOnce(first).mockResolvedValueOnce(second);
    const { api, unmount } = mountConsumer();

    await api.queryPools();
    await flushPromises();
    expect(api.selectPool(api.pools.value[0])).toBe(true);
    expect(api.selectedPool.value?.id).toBe('501');

    await api.queryPools();
    await flushPromises();
    expect(api.selectedPool.value).toBeNull();
    expect(api.pools.value.length).toBe(2);
    unmount();
  });

  it('身份准入守卫：canQuery 为假零出网（卡片置灰的双保险）', async () => {
    vi.mocked(listPortalPools).mockResolvedValue([]);
    const { api, unmount } = mountConsumer(() => false);

    await api.queryPools();
    await flushPromises();

    expect(vi.mocked(listPortalPools)).not.toHaveBeenCalled();
    expect(api.poolsLoading.value).toBe(false);
    unmount();
  });

  it('在途防重入：慢响应窗口内重复查询只出网一次且骨架态持续', async () => {
    let releaseQuery: () => void = () => {};
    vi.mocked(listPortalPools).mockImplementation(
      () =>
        new Promise<NumberPoolVO[]>((resolve) => {
          releaseQuery = () => resolve([poolMock('501', 6)]);
        }),
    );
    const { api, unmount } = mountConsumer();

    void api.queryPools();
    await flushPromises();
    expect(api.poolsLoading.value).toBe(true);

    await api.queryPools();
    await flushPromises();
    expect(vi.mocked(listPortalPools)).toHaveBeenCalledTimes(1);

    releaseQuery();
    await flushPromises();
    expect(api.poolsLoading.value).toBe(false);
    unmount();
  });

  it('OP-1007 错误码映射：PortalApiError 转译患者可读文案（真实 resolveErrorCopy）', async () => {
    vi.mocked(listPortalPools).mockRejectedValue(
      new PortalApiError('患者冻结拦截', 'OP-1007', 409),
    );
    const { api, unmount } = mountConsumer();

    await api.queryPools();
    await flushPromises();

    expect(api.poolsError.value).toBe('该证件存在未完成缴费的挂号，请先完成缴费');
    expect(api.pools.value).toEqual([]);
    unmount();
  });

  it('网络异常兜底：非业务错误统一「网络异常，请稍后重试」', async () => {
    vi.mocked(listPortalPools).mockRejectedValue(new Error('timeout'));
    const { api, unmount } = mountConsumer();

    await api.queryPools();
    await flushPromises();

    expect(api.poolsError.value).toBe('网络异常，请稍后重试');
    unmount();
  });

  it('落选守卫：余 0 拒选零副作用（返回 false 不落选），余量正常落选返回 true', () => {
    const { api, unmount } = mountConsumer();

    expect(api.selectPool(poolMock('501', 0))).toBe(false);
    expect(api.selectedPool.value).toBeNull();

    expect(api.selectPool(poolMock('502', 1))).toBe(true);
    expect(api.selectedPool.value?.id).toBe('502');
    unmount();
  });

  it('复位号源面：resetPools 清列表与已选（「再约一个」回第 1 步）', async () => {
    vi.mocked(listPortalPools).mockResolvedValue([poolMock('501', 6)]);
    const { api, unmount } = mountConsumer();

    await api.queryPools();
    await flushPromises();
    expect(api.selectPool(api.pools.value[0])).toBe(true);

    api.resetPools();
    expect(api.pools.value).toEqual([]);
    expect(api.selectedPool.value).toBeNull();
    unmount();
  });
});
