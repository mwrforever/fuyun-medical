/**
 * ECharts 图表实例生命周期组合（web 宪法 B.3-5：init/setOption/resize/dispose 统一封装）：
 * 容器挂载后惰性 init、option 变更经 refresh 全量 setOption、窗口 resize 监听随实例生命周期
 * 挂/摘（防泄漏）、页面隐藏 dispose / 可见重建（页面隐藏必须 dispose 防实例泄漏）、组件卸载
 * 全量清理（resize 监听 + visibilitychange 监听 + dispose）。
 *
 * <p>图表色值消费口径：option 由调用方构建（色值读 tokens.css 既有语义变量，禁自创色值），
 * 本组合不持有任何视觉常量；option 以 getter 注入（每次 refresh 重新求值，适配实时数据帧）。
 */
import { onUnmounted, ref, watch } from 'vue';
import type { Ref } from 'vue';
import type { EChartsInstance, EChartsOption } from '@/utils/echarts';
import { initChart } from '@/utils/echarts';

/**
 * 图表实例生命周期组合入口。
 *
 * @param container 图表容器模板引用（useTemplateRef 产物，只读消费；null=未挂载，挂载后自动
 *   init）；模板引用在挂载后才有值，卸载后归 null
 * @param getOption option 构建器（refresh 时重新求值；图表未初始化期间的调用被忽略，
 *   初始化完成后调用方须主动 refresh 一次补渲染）
 * @return refresh 按当前 option 重渲染（未初始化时幂等跳过）
 */
export function useEChart(
  container: Readonly<Ref<HTMLElement | null>>,
  getOption: () => EChartsOption,
): { refresh: () => void } {
  /** 图表实例（null=未初始化；页面隐藏 dispose 后回归 null） */
  const instance = ref<EChartsInstance | null>(null);

  /** init 并按当前 option 首渲染（幂等：已有实例时跳过；容器 null 时忽略） */
  function ensureInit(): void {
    const el = container.value;
    if (el === null || instance.value !== null) {
      return;
    }
    instance.value = initChart({ dom: el });
    instance.value.setOption(getOption(), true);
  }

  /** 销毁实例（幂等：null 时跳过；resize 监听由 detachResize 统一摘除） */
  function disposeChart(): void {
    instance.value?.dispose();
    instance.value = null;
  }

  /** 窗口尺寸变化重绘（echarts 内建节流封装，仅重绘语义无布局动画） */
  function handleResize(): void {
    instance.value?.resize();
  }

  /** 挂载窗口 resize 监听（随实例生命周期，重复挂载前先摘除防重复注册） */
  function attachResize(): void {
    window.removeEventListener('resize', handleResize);
    window.addEventListener('resize', handleResize);
  }

  /** 摘除窗口 resize 监听（幂等） */
  function detachResize(): void {
    window.removeEventListener('resize', handleResize);
  }

  /** 页面可见性翻转：隐藏 dispose（GPU/实例释放）、可见重建（B.3-5 页面隐藏必须 dispose） */
  function handleVisibility(): void {
    if (document.hidden) {
      disposeChart();
      return;
    }
    ensureInit();
  }

  /** 按当前 option 全量重渲染（notMerge=true 防旧 series 残留；未初始化时幂等跳过） */
  function refresh(): void {
    instance.value?.setOption(getOption(), true);
  }

  // 容器模板引用在挂载后才有值：flush post 时机监听，出现即 init 挂监听、消失即销毁摘监听
  watch(
    container,
    (el) => {
      if (el !== null) {
        ensureInit();
        attachResize();
      } else {
        disposeChart();
        detachResize();
      }
    },
    { immediate: true, flush: 'post' },
  );

  document.addEventListener('visibilitychange', handleVisibility);

  // 卸载全量清理（B.3-5 卸载必须 dispose）：resize 监听 + visibilitychange 监听 + 实例
  onUnmounted(() => {
    document.removeEventListener('visibilitychange', handleVisibility);
    detachResize();
    disposeChart();
  });

  return { refresh };
}
