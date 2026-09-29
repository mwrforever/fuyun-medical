/**
 * portal 可约号源查询与选择（FU-M03-02 §8.4）：诊区/日期变化即查（进入第 2 步自动首查），
 * 刷新后清空已选防携带失效选择提交；余 0 号源禁选。自 AppointmentView 下沉（EX-48 巨型组件
 * 收口，web 宪法 B.3-6）：查询守卫/失败文案映射与原内联实现逐字一致（身份未完成不可查、
 * 在途防重入、PortalApiError 走错误码转译文案），选中后的滚动展开等视图动作归调用方承接。
 */
import { ref } from 'vue';
import type { Ref } from 'vue';
import { listPortalPools, resolveErrorCopy } from '@/api/outpatient';
import type { NumberPoolVO } from '@/api/outpatient';
import { PortalApiError } from '@/api/http';

/** useNumberPools 注入面（查询条件 ref + 准入守卫，composable 不反向依赖视图层） */
export interface UseNumberPoolsOptions {
  /** 诊区编码（响应式：诊区 chip 切换后由调用方触发重查） */
  deptCode: Readonly<Ref<string>>;
  /** 就诊日（yyyy-MM-dd，响应式：日期 chip 切换后由调用方触发重查） */
  schedDate: Readonly<Ref<string>>;
  /** 查询准入守卫（false=身份未完成不可查——卡片置灰由模板承载，此处双保险零出网） */
  canQuery: () => boolean;
}

/** useNumberPools 返回面（页面消费列表/骨架/错误条驱动第 2 步渲染） */
export interface UseNumberPoolsApi {
  /** 号源池列表（空列表=当日无可约号源，模板渲染空态文案） */
  pools: Readonly<Ref<NumberPoolVO[]>>;
  /** 查询中标志（true=骨架屏） */
  poolsLoading: Readonly<Ref<boolean>>;
  /** 查询失败文案（错误码映射后的患者可读文案；空串=无错误） */
  poolsError: Readonly<Ref<string>>;
  /** 已选号源（null=未选；第 3 步解锁与提交载荷的判定源） */
  selectedPool: Readonly<Ref<NumberPoolVO | null>>;
  /** 查询可约号源（守卫：准入未过/在途查询中直接返回，防重复出网） */
  queryPools: () => Promise<void>;
  /** 选中号源（余 0 拒选；返回是否选中——true 时调用方承接滚动展开第 3 步） */
  selectPool: (pool: NumberPoolVO) => boolean;
  /** 复位号源面（「再约一个」：清列表与已选） */
  resetPools: () => void;
}

/**
 * 号源查询与选择组合入口（仅 setup 同步调用）。
 *
 * @param options 查询条件与准入守卫（见各字段注释）
 * @return 列表/选中态 + 查询/选择/复位函数
 */
export function useNumberPools(options: UseNumberPoolsOptions): UseNumberPoolsApi {
  const pools = ref<NumberPoolVO[]>([]);
  const poolsLoading = ref(false);
  const poolsError = ref('');
  const selectedPool = ref<NumberPoolVO | null>(null);

  /**
   * 查询可约号源：准入守卫（身份未完成/在途查询）→ 出网 → 刷新后清空已选（防携带失效
   * 选择提交）→ 失败按错误码映射文案；finally 复位查询中标志。
   */
  async function queryPools(): Promise<void> {
    if (!options.canQuery() || poolsLoading.value) {
      return;
    }
    poolsLoading.value = true;
    poolsError.value = '';
    try {
      pools.value = await listPortalPools({
        deptCode: options.deptCode.value,
        date: options.schedDate.value,
      });
      selectedPool.value = null;
    } catch (error: unknown) {
      poolsError.value =
        error instanceof PortalApiError ? resolveErrorCopy(error) : '网络异常，请稍后重试';
    } finally {
      poolsLoading.value = false;
    }
  }

  /**
   * 选中号源卡（余 0 禁点：模板已置灰禁用，此处守卫双保险）。
   *
   * @param pool 号源池（来源：列表渲染项用户点击）
   * @return 是否选中（true=已落选，调用方滚动展开第 3 步；false=余 0 拒选零副作用）
   */
  function selectPool(pool: NumberPoolVO): boolean {
    if ((pool.remaining ?? 0) <= 0) {
      return false;
    }
    selectedPool.value = pool;
    return true;
  }

  /** 复位号源面（列表清空 + 已选清空；「再约一个」回第 1 步时调用） */
  function resetPools(): void {
    selectedPool.value = null;
    pools.value = [];
  }

  return { pools, poolsLoading, poolsError, selectedPool, queryPools, selectPool, resetPools };
}
