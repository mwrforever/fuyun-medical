/**
 * ECharts 按需注册单一模块（web 宪法 B.3-5 唯一注册点）：echarts/core + 按图表类型/组件注册
 * + 手动 Canvas 渲染器（二选一渲染器按需取 Canvas——大屏暗底高频重绘场景 GPU 合成友好），
 * 控制包体（禁全量 `import echarts from 'echarts'`）。全 app 所有图表消费必须经本模块导入
 * init 与类型，禁组件直连 echarts 包（注册集中防漏注册）。
 *
 * <p>当前注册面：折线图（遥测趋势 min/max/avg 三线）+ 直角坐标系 + 提示框。后续图表类型
 * 随大屏面演进在此追加 use 注册，页面零改动。
 */
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { ComposeOption, EChartsInitOpts, EChartsType } from 'echarts/core';
import type { LineSeriesOption } from 'echarts/charts';
import type { GridComponentOption, TooltipComponentOption } from 'echarts/components';

// 按需注册（模块加载期一次执行，全 app 共享注册结果；重复 use 幂等无副作用）
echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

/** 严格类型 option（ComposeOption 组合：仅允许已注册组件的 option 片段，B.3-5） */
export type EChartsOption = ComposeOption<
  LineSeriesOption | GridComponentOption | TooltipComponentOption
>;

/** 图表实例类型（dispose/resize/setOption 消费面） */
export type EChartsInstance = EChartsType;

/** 图表初始化入参（透传 echarts/core.init：dom 必填、theme 可空、opts 可空） */
export interface ChartInitOptions {
  dom: HTMLElement;
  theme?: string | undefined;
  opts?: EChartsInitOpts | undefined;
}

/**
 * 初始化图表实例（echarts/core.init 代理）：注册已在本模块集中完成后方可用；容器须已挂载
 * 且具有布局尺寸（零尺寸容器由 useEChart 的挂载时机保证）。
 *
 * @param options 初始化入参（dom/theme/opts）
 * @return 图表实例（调用方持有，dispose/resize/setOption 经实例操作）
 */
export function initChart(options: ChartInitOptions): EChartsInstance {
  return echarts.init(options.dom, options.theme, options.opts);
}
