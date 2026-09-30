/**
 * 三段式分页列表范式（EX-49 沉淀，组合 useAsyncTask 承载 loading 骨架）：收拢视图内
 * 「查询参数 ref + 分页加载函数（page/size + 行集/总数赋值）+ loading 状态」三段式样板
 * （workstation 实证 14 处/14 文件，含固定首页清单与 UI 翻页两形态）。
 *
 * 职责边界（最小 API 面）：本 composable 只管页码态（1 基对外、0 基对契约的边界转换在
 * 此收口）+ 行集/总数/加载三态 + 出网合并；检索词等查询条件仍归视图持有，经 params 快照
 * 工厂在每次发起时实时取值（空串转 undefined 等归一由调用方承载，与既有视图逐字一致）。
 * 固定首页清单场景（page 恒 0 的 size 50 直出）只调 fetch 不暴露翻页 UI 即可，currentPage
 * 态闲置无害。失败分支默认静默（弹错归响应拦截器）、行集驻留旧值，与既有样板一致；成功
 * 后追加副作用（如 searched 置位、勾选集清空）经 onSuccess 注入。
 *
 * 每组件实例独立状态（B.2-7）；仅 setup 同步调用；无 onUnmounted 清理面。并发语义与被
 * 替换样板逐字一致：不设在途互斥，慢回包竞态由各视图按需另行锚定。
 */
import { ref } from 'vue';
import type { Ref } from 'vue';
import { useAsyncTask } from './useAsyncTask';

/** 分页出参契约（后端分页出参前端面；content/total 可缺失，缺失按空数据/0 兜底） */
export interface PageResult<R> {
  /** 当页行集（契约外缺失兜底空数组，防 undefined 穿透渲染层） */
  content?: R[];
  /** 总条数（number/string 双形态承载——后端雪花计数口径有 string 输出面） */
  total?: number | string;
}

/** usePagedList 参数（web A.7-1 形参对象化） */
export interface UsePagedListOptions<Q extends object, R> {
  /**
   * 查询条件快照工厂：每次发起（fetch/search/goToPage）实时取值合并进出网入参，
   * 保证发起与回包之间改条件不串页。
   */
  params: () => Q;
  /**
   * 分页出网函数：入参为 params() 快照合并 0 基 page 与 size（api 层保持 0 基契约纯透传）。
   */
  fetcher: (query: Q & { page: number; size: number }) => Promise<PageResult<R>>;
  /** 单页条数（默认 20；固定首页清单场景按各页后端契约值传入，如 50） */
  pageSize?: number;
  /**
   * 成功分支追加钩子（可选）：行集/总数赋值之后执行（如 searched 置位区分「初始未查」
   * 与「查无结果」、重载后清空勾选集）。
   *
   * @param result 出网原始分页出参（未兜底加工前形态）
   */
  onSuccess?: (result: PageResult<R>) => void;
  /**
   * 失败分支钩子（可选）：默认静默——弹错归响应拦截器；需兜底展示的场景注入
   * surfaceBizError。失败时行集/总数驻留旧值（与既有样板一致）。
   */
  onError?: (error: unknown) => void;
}

/** usePagedList 返回态（loading/error 只读外泄；currentPage 可写供翻页组件 v-model 直连） */
export interface UsePagedListReturn<R> {
  /** 当页行集（模板表格 :data 直连） */
  rows: Ref<R[]>;
  /** 总条数（number 归一后口径；分页条 total 直连） */
  total: Readonly<Ref<number>>;
  /** 加载态（v-loading/:loading 直连） */
  loading: Readonly<Ref<boolean>>;
  /** 最近一次失败对象（null=尚无失败；透传 useAsyncTask 口径） */
  error: Readonly<Ref<unknown>>;
  /** 当前页码（1 基，分页组件绑定口径；出网时内部转 0 基） */
  currentPage: Ref<number>;
  /** 单页条数（创建时定值，无运行中改页需求——既有视图均静态页宽） */
  pageSize: number;
  /** 以当前页码与当前查询条件发起加载（onMounted 初拉/操作后重刷共用出口） */
  fetch: () => Promise<void>;
  /** 回第一页发起（换检索条件/点查询按钮出口） */
  search: () => Promise<void>;
  /**
   * 跳指定页发起（分页组件 current-change 出口）。
   *
   * @param page 目标页码（1 基，分页组件回传口径）
   */
  goToPage: (page: number) => Promise<void>;
}

/** 总条数归一：string/number 双形态与契约外缺失统一收敛 number（缺失/非法按 0） */
function toTotalNumber(total: number | string | undefined): number {
  const parsed = Number(total ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

/**
 * 建立三段式分页列表状态面（EX-49 范式落点，内部经 useAsyncTask 承载 loading 骨架）。
 *
 * @param options 分页参数（params 快照工厂 + fetcher 出网函数必填，其余可选）
 * @return 行集/总数/加载/页码态 + fetch/search/goToPage 三个发起出口
 */
export function usePagedList<Q extends object, R>(
  options: UsePagedListOptions<Q, R>,
): UsePagedListReturn<R> {
  const pageSize = options.pageSize ?? 20;
  const rows = ref<R[]>([]) as Ref<R[]>;
  const total = ref(0);
  const currentPage = ref(1);

  const task = useAsyncTask(
    async () => {
      const result = await options.fetcher({
        ...options.params(),
        // 边界转换：组件 currentPage 1 基 → 契约 page 0 基（api 层保持纯透传）
        page: currentPage.value - 1,
        size: pageSize,
      });
      rows.value = result.content ?? [];
      total.value = toTotalNumber(result.total);
      options.onSuccess?.(result);
    },
    { onError: options.onError },
  );

  async function fetch(): Promise<void> {
    await task.run();
  }

  async function search(): Promise<void> {
    currentPage.value = 1;
    await fetch();
  }

  async function goToPage(page: number): Promise<void> {
    currentPage.value = page;
    await fetch();
  }

  return {
    rows,
    total,
    loading: task.loading,
    error: task.error,
    currentPage,
    pageSize,
    fetch,
    search,
    goToPage,
  };
}
