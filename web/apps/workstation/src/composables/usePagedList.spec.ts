// 三段式分页列表范式单测（EX-49 范式沉淀）：fetcher 注入桩函数承载（无 api mock 面），覆盖
// 正常（快照合并出网/行集总数赋值）、边界（1↔0 基转换、search 回首页、content/total 缺失
// 兜底、string 总数归一、默认页宽 20）、异常（失败驻留旧值、onError 注入、onSuccess 时机）
// 三类场景。fetcher 桩用 Promise.resolve 直构（非 async 箭头），与被测 await 语义等价。
import { describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { usePagedList } from './usePagedList';
import type { PageResult } from './usePagedList';

/** 分页行桩（业务字段最小面） */
interface Row {
  id: string;
}

/** 出参桩（可覆写 content/total） */
function pageOf(content: Row[], total: number | string): PageResult<Row> {
  return { content, total };
}

describe('usePagedList', () => {
  it('fetch 以当前查询条件快照 + 0 基页码 + 页宽合并出网，行集/总数赋值', async () => {
    const keyword = ref('张三');
    const fetcher = vi.fn(() => Promise.resolve(pageOf([{ id: '1' }, { id: '2' }], '2')));
    const list = usePagedList({
      params: () => ({ keyword: keyword.value }),
      fetcher,
      pageSize: 20,
    });
    await list.fetch();
    expect(fetcher).toHaveBeenCalledExactlyOnceWith({ keyword: '张三', page: 0, size: 20 });
    expect(list.rows.value).toHaveLength(2);
    expect(list.total.value).toBe(2);
    expect(list.loading.value).toBe(false);
  });

  it('goToPage 承载 1↔0 基边界转换（分页组件第二页 → 契约 page=1）', async () => {
    const fetcher = vi.fn(() => Promise.resolve(pageOf([], 0)));
    const list = usePagedList({ params: () => ({}), fetcher, pageSize: 50 });
    await list.goToPage(2);
    expect(list.currentPage.value).toBe(2);
    expect(fetcher).toHaveBeenLastCalledWith({ page: 1, size: 50 });
  });

  it('search 回第一页发起（换检索条件出口），翻页后再检索不串页', async () => {
    const fetcher = vi.fn(() => Promise.resolve(pageOf([], 0)));
    const list = usePagedList({ params: () => ({}), fetcher });
    await list.goToPage(3);
    await list.search();
    expect(list.currentPage.value).toBe(1);
    expect(fetcher).toHaveBeenLastCalledWith({ page: 0, size: 20 });
  });

  it('params 快照在每次发起时实时取值（两次 fetch 之间改条件各自生效）', async () => {
    const statusFilter = ref('');
    // 桩仅声明 query 签名（状态过滤 + 分页参数）供 mock.calls 入参断言取值，出参走 mockResolvedValue
    const fetcher =
      vi.fn<(query: { status?: string; page: number; size: number }) => Promise<PageResult<Row>>>();
    fetcher.mockResolvedValue(pageOf([], 0));
    const list = usePagedList({
      params: () => ({ status: statusFilter.value === '' ? undefined : statusFilter.value }),
      fetcher,
    });
    await list.fetch();
    statusFilter.value = 'PENDING';
    await list.fetch();
    expect(fetcher.mock.calls[0]?.[0]).toEqual({ status: undefined, page: 0, size: 20 });
    expect(fetcher.mock.calls[1]?.[0]).toEqual({ status: 'PENDING', page: 0, size: 20 });
  });

  it('契约外兜底：content 缺失按空数组、total 缺失按 0、string 总数归一 number', async () => {
    const fetcher = vi.fn<() => Promise<PageResult<Row>>>();
    const list = usePagedList({ params: () => ({}), fetcher });
    fetcher.mockResolvedValueOnce({});
    await list.fetch();
    expect(list.rows.value).toEqual([]);
    expect(list.total.value).toBe(0);
    fetcher.mockResolvedValueOnce(pageOf([{ id: '9' }], '123'));
    await list.fetch();
    expect(list.rows.value).toEqual([{ id: '9' }]);
    expect(list.total.value).toBe(123);
  });

  it('onSuccess 在成功赋值后触发（searched 置位/勾选集清空承载面），失败不触发', async () => {
    const onSuccess = vi.fn();
    const fetcher = vi.fn<() => Promise<PageResult<Row>>>();
    const list = usePagedList({ params: () => ({}), fetcher, onSuccess });
    const first = pageOf([{ id: '1' }], 1);
    fetcher.mockResolvedValueOnce(first);
    await list.fetch();
    expect(onSuccess).toHaveBeenCalledExactlyOnceWith(first);
    fetcher.mockRejectedValueOnce(new Error('出网失败'));
    await list.fetch();
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it('失败默认静默驻留旧值：行集/总数不清空、loading 复位、onError 注入接收原因', async () => {
    const cause = new Error('接口超时');
    const onError = vi.fn();
    const fetcher = vi.fn<() => Promise<PageResult<Row>>>();
    const list = usePagedList({ params: () => ({}), fetcher, onError, pageSize: 20 });
    fetcher.mockResolvedValueOnce(pageOf([{ id: '1' }], 1));
    await list.fetch();
    fetcher.mockRejectedValueOnce(cause);
    await list.fetch();
    expect(list.rows.value).toEqual([{ id: '1' }]);
    expect(list.total.value).toBe(1);
    expect(list.loading.value).toBe(false);
    expect(list.error.value).toBe(cause);
    expect(onError).toHaveBeenCalledExactlyOnceWith(cause);
  });
});
