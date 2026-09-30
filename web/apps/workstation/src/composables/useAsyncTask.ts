/**
 * 异步任务三态包装（EX-42 通用范式沉淀，web 宪法 B.2-6「数据获取逻辑 loading/error 一律进
 * composables」）：收拢视图内 `const loading = ref(false)` + async 函数 try/finally 翻转的
 * loading 骨架样板（workstation 实证 41 处/26 文件）。
 *
 * 设计取舍（为何 catch 不内建弹错）：既有视图的失败分支存在差异行为——大多数静默（弹错归
 * 响应拦截器，web A.3-2）、少数经 surfaceBizError 兜底展示、个别无 catch 依赖异常上抛——
 * 故失败处置经 onError 钩子注入、不强求一律；默认静默对齐占绝对多数的拦截器口径。数据
 * （data 态）不收拢：既有加载函数的出参加工形态各异（过滤/排序/多状态赋值），收拢反而
 * 失配，由任务函数体内自行赋值，run 原样透传出参供简单场景取值。
 *
 * 每组件实例独立状态（B.2-7）；仅 setup 同步调用；无副作用需 onUnmounted 清理（loading
 * 为同步翻转的普通 ref，不涉定时器/订阅句柄）。并发语义与被替换的骨架逐字一致：不设在途
 * 互斥/竞态守卫（原样板的慢回包竞态由各视图按需另行锚定，如回包比对 visitId 过期丢弃）。
 */
import { ref } from 'vue';
import type { Ref } from 'vue';

/** 异步任务包装参数（web A.7-1 形参对象化） */
export interface UseAsyncTaskOptions {
  /**
   * 失败分支钩子（可选）：默认静默——弹错归响应拦截器（AxiosError 已防双弹口径）；
   * 需兜底展示的场景注入 surfaceBizError，需保持异常上抛的场景（原版无 catch）可在此重抛。
   *
   * @param error 任务抛出的未知异常（api 层 AxiosError 或业务拒绝对象）
   */
  onError?: (error: unknown) => void;
  /**
   * finally 追加钩子（可选）：loading 复位之外的成功/失败共用收尾（如抽屉空态复位、
   * 行级在途锚点清空）；在 loading 置 false 之后执行。
   */
  onFinally?: () => void;
}

/** useAsyncTask 返回态（loading/error 只读外泄，禁调用方直写翻转） */
export interface UseAsyncTaskReturn<T, A extends unknown[]> {
  /** 加载态（模板 v-loading/:loading 直连；run 在途期间为 true） */
  loading: Readonly<Ref<boolean>>;
  /** 最近一次失败对象（null=尚无失败；每次 run 发起时清零，成功不回写） */
  error: Readonly<Ref<unknown>>;
  /**
   * 包装后的任务（与原任务同参同名替换调用点）：发起即置 loading，成功原样透传出参、
   * 失败收拢 error 后返回 undefined（不再上抛，调用方 await 后按返回值判空即知成败）。
   */
  run: (...args: A) => Promise<T | undefined>;
}

/**
 * 包装异步任务为带 loading/error 三态的 run 函数（EX-42 范式落点）。
 *
 * @param task 业务任务函数（出网与出参加工在此承载；抛错即失败分支）
 * @param options 包装参数（onError/onFinally 钩子，均可选）
 * @return loading/error 只读态 + run 包装函数
 */
export function useAsyncTask<T, A extends unknown[]>(
  task: (...args: A) => Promise<T>,
  options?: UseAsyncTaskOptions,
): UseAsyncTaskReturn<T, A> {
  const loading = ref(false);
  const error = ref<unknown>(null);

  async function run(...args: A): Promise<T | undefined> {
    loading.value = true;
    // 失败态按次清零：上一轮失败不驻留到本轮（成功路径 error 恒为 null）
    error.value = null;
    try {
      return await task(...args);
    } catch (cause) {
      // 失败先同步落 error 再交 onError：onError 重抛路径下 error.value 也已置位——调用方
      // 择一消费即可（钩子内取参数，或钩子外读 error.value），禁两路重复处置同一失败
      error.value = cause;
      options?.onError?.(cause);
      return undefined;
    } finally {
      loading.value = false;
      options?.onFinally?.();
    }
  }

  return { loading, error, run };
}
