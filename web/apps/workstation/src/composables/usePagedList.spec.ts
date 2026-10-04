// 三段式分页列表范式单测（EX-49 范式沉淀）：fetcher 注入桩函数承载（无 api mock 面），覆盖
// 正常（快照合并出网/行集总数赋值）、边界（1↔0 基转换、search 回首页、content/total 缺失
// 兜底、string 总数归一、默认页宽 20）、异常（失败驻留旧值、onError 注入、onSuccess 时机）
// 三类场景，另含 D-3 慢回包守卫（筛选切换/翻页往返旧回包丢弃）。fetcher 桩用 Promise.resolve
// 直构（非 async 箭头），与被测 await 语义等价；慢回包用例以手动闸门两段式 resolve 控制回包先后。
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

  it('D-3 慢回包守卫：筛选切换后旧回包后到被丢弃（rows 不被旧筛选覆盖）', async () => {
    const filter = ref('x');
    // 手动闸门两段式：按调用序捕获各次回包 resolve 器，测试侧控制回包先后（模拟慢回包晚到）
    const gates: Array<(result: PageResult<Row>) => void> = [];
    const fetcher = vi.fn(
      () =>
        new Promise<PageResult<Row>>((resolve) => {
          gates.push(resolve);
        }),
    );
    const list = usePagedList({ params: () => ({ filter: filter.value }), fetcher });
    const fetchA = list.fetch(); // 请求 A：旧筛选 filter='x'
    filter.value = 'y';
    const fetchB = list.fetch(); // 请求 B：新筛选 filter='y'（后发起，回包前后均晚于 A 落地）
    // 旧请求 A 慢回包先 resolve（B 仍在途）：A 已过期必须丢弃，rows 不得落旧筛选内容
    gates[0]?.(pageOf([{ id: 'A1' }], 1));
    await fetchA;
    expect(list.rows.value).toEqual([]);
    // 新请求 B 回包：最新请求正常落值
    gates[1]?.(pageOf([{ id: 'B1' }], 1));
    await fetchB;
    expect(list.rows.value).toEqual([{ id: 'B1' }]);
  });

  it('D-3 慢回包守卫：翻页快速往返旧页回包丢弃', async () => {
    // 手动闸门两段式：翻到第 2 页后立即回第 1 页，第 2 页慢回包晚到不得落值
    const gates: Array<(result: PageResult<Row>) => void> = [];
    const fetcher = vi.fn(
      () =>
        new Promise<PageResult<Row>>((resolve) => {
          gates.push(resolve);
        }),
    );
    const list = usePagedList({ params: () => ({}), fetcher });
    const page2 = list.goToPage(2); // 快速翻到第 2 页后立即返回第 1 页
    const page1 = list.goToPage(1);
    // 第 2 页慢回包先 resolve（第 1 页在途）：过期丢弃，rows 不得落第 2 页内容
    gates[0]?.(pageOf([{ id: 'p2' }], 1));
    await page2;
    expect(list.rows.value).toEqual([]);
    // 第 1 页回包：最新请求正常落值
    gates[1]?.(pageOf([{ id: 'p1' }], 1));
    await page1;
    expect(list.rows.value).toEqual([{ id: 'p1' }]);
  });
});
