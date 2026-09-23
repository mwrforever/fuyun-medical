/**
 * 体温单坐标计算纯函数（设计文档 §5 唯一权威）：体征月页条目 + 体征值行 → 符号/连线/事件/
 * 网格描述数组，视图层仅做 SVG 映射渲染。坐标分度冻结：Y 轴 35.0–42.0℃ 每小格 0.2℃（脉搏轴
 * 20–160 次/分共格、每小格 4 次），X 轴每小格 2 小时（每天 12 小格 192px）；符号类名契约
 * （fuy-temp-x/dot/circle、fuy-temp-deficit-line 等）冻结于 §5.3，spec 机器判据来源。
 * 零图表库、零内联色值（stroke/fill 全经视图 scoped 类消费 token）。
 */
import type { TemperatureChartVO, VitalSignVO } from '@/api/nursing';
import { TEMP_SITE_SYMBOL } from '@/api/nursing';

/** Y 轴体温域冻结值（设计文档 §5.2 + 自查清单 #4：存在且被坐标计算消费） */
export const TEMP_MIN = 35;
export const TEMP_MAX = 42;
export const TEMP_STEP = 0.2;
/** Y 轴脉搏域冻结值（与体温 35 小格完全共格：每小格 4 次/分） */
export const PULSE_MIN = 20;
export const PULSE_MAX = 160;
export const PULSE_STEP = 4;
/** X 轴几何：每小格 2 小时、每天 12 小格 */
export const GRID_X = 16;
export const GRID_Y = 8;
export const DAY_CELLS = 12;
export const DAY_WIDTH = GRID_X * DAY_CELLS; // 192px/天
/** 曲线区高：35 小格 × 8px = 280px */
export const CHART_HEIGHT = Math.round((TEMP_MAX - TEMP_MIN) / TEMP_STEP) * GRID_Y;
/** 左右刻度列宽（左体温右脉搏双刻度） */
export const AXIS_WIDTH = 32;
/** 连线断点阈值：相邻时点缺口 ≥2 小格（4 小时）不跨缺口连线（§5.5-4 防趋势臆造） */
export const LINE_GAP_LIMIT = 2 * GRID_X;

/** 事件竖线修饰符映射（§5.3 S11/S12 冻结：--admission/--surgery/--delivery/--transfer/--discharge/--death/--arrest） */
const EVENT_MODIFIERS: Record<string, string> = {
  ADMISSION: 'fuy-event-line--admission',
  SURGERY: 'fuy-event-line--surgery',
  DELIVERY: 'fuy-event-line--delivery',
  TRANSFER_OUT: 'fuy-event-line--transfer',
  DISCHARGE: 'fuy-event-line--discharge',
  DEATH: 'fuy-event-line--death',
  CARDIAC_ARREST: 'fuy-event-line--arrest',
};

/** 事件竖线旁标注文案（特殊事件类型词表展示映射） */
const EVENT_LABELS: Record<string, string> = {
  ADMISSION: '入院',
  SURGERY: '手术',
  DELIVERY: '分娩',
  TRANSFER_OUT: '转科',
  DISCHARGE: '出院',
  DEATH: '死亡',
  PHYSICAL_COOLING: '物理降温',
  PULSE_DEFICIT_START: '脉搏短绌起',
  PULSE_DEFICIT_END: '脉搏短绌止',
  CARDIAC_ARREST: '呼吸心跳停止',
};

/** 脉搏短绌填充线固定跨度（2 小格=8 次/分）：P1 体征面无心率值，短绌填充线自脉率点向上
 * 画 2 小格作标记；P2 心率曲线接入后按脉率-心率两值连线（偏差登记见任务报告） */
const DEFICIT_SPAN = 2 * GRID_Y;

/** 数据点符号节点（×/●/〇/脉率点/降温红圈/重叠外圈） */
export interface ChartSymbolNode {
  key: string;
  /** 符号类名（fuy-temp-x / fuy-temp-dot / fuy-temp-circle / fuy-pulse-dot / fuy-temp-cooling-ring / fuy-temp-overlap-ring） */
  className: string;
  x: number;
  y: number;
  /** 无障碍读法与原生 hover 提示（§5.4：每数据点符号附 title） */
  title: string;
}

/** 连线节点（体温/脉率/降温虚线/短绌填充线） */
export interface ChartLineNode {
  key: string;
  className: string;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

/** 特殊事件竖线节点 */
export interface ChartEventNode {
  key: string;
  className: string;
  x: number;
  label: string;
  /** 呼吸心跳停止双竖线（§5.3 S12：两条 1px 竖线间距 2px） */
  double: boolean;
}

/** 网格线节点（major=1℃ 线/日隔线） */
export interface ChartGridNode {
  key: string;
  major: boolean;
  /** 竖线 x / 横线 y */
  pos: number;
  vertical: boolean;
}

/** 日行值底栏单元格（按天 × 类型展开） */
export interface DailyCell {
  day: number;
  /** 类型行键（DESIGN/STAY_DAYS/SURGERY_DAYS/STOOL/INTAKE/OUTPUT/WEIGHT/HEIGHT） */
  rowKey: string;
  valueText: string;
  /** 班次小结/24h 总结红双线类（§5.3 S14/S15） */
  ruleClass?: string;
}

/** 体温单渲染模型（纯函数输出，视图层零计算直渲） */
export interface TempChartModel {
  /** 月内天数 */
  days: number;
  /** SVG 总宽（左刻度列 + 曲线区 + 右刻度列） */
  width: number;
  height: number;
  symbols: ChartSymbolNode[];
  lines: ChartLineNode[];
  events: ChartEventNode[];
  grids: ChartGridNode[];
  /** 左轴体温刻度（35–42 整数度） */
  tempTicks: Array<{ value: number; y: number }>;
  /** 右轴脉搏刻度（20–160 每 20 一档） */
  pulseTicks: Array<{ value: number; y: number }>;
  /** X 轴日号刻度（1..days） */
  dayTicks: Array<{ day: number; x: number }>;
  dailyCells: DailyCell[];
}

/** 日行值行键 → 体温单 DAILY_VALUE dailyValueType 映射（P1 生产者仅出入量小结；
 * 体重/身高等行由后端聚合接入后自动填充，前端零内置值） */
const DAILY_ROW_OF_TYPE: Record<string, string> = {
  IO_SUMMARY_SHIFT: 'INTAKE',
  IO_SUMMARY_24H: 'INTAKE',
};

/** 日行值行定义（§5.7 冻结八行：日期/住院天数/手术后天数/大便次数/入量/出量/体重/身高） */
export const DAILY_ROWS: ReadonlyArray<{ key: string; label: string }> = [
  { key: 'DATE', label: '日期' },
  { key: 'STAY_DAYS', label: '住院天数' },
  { key: 'SURGERY_DAYS', label: '手术后天数' },
  { key: 'STOOL', label: '大便次数' },
  { key: 'INTAKE', label: '入量' },
  { key: 'OUTPUT', label: '出量' },
  { key: 'WEIGHT', label: '体重' },
  { key: 'HEIGHT', label: '身高' },
];

/** 时点 → X 坐标（含左刻度列偏移）：吸附最近小格中心（点不落格线，临床画法惯例 §5.2） */
function xOf(time: string, monthStartMs: number): number {
  const date = new Date(time);
  const day = Math.floor((date.getTime() - monthStartMs) / 86400000);
  if (day < 0) {
    return AXIS_WIDTH;
  }
  const cell = Math.floor(date.getHours() / 2);
  return AXIS_WIDTH + day * DAY_WIDTH + cell * GRID_X + GRID_X / 2;
}

/** 体温值 → Y 坐标（42℃ 顶 / 35℃ 底） */
function yOfTemp(temp: number): number {
  return ((TEMP_MAX - temp) / TEMP_STEP) * GRID_Y;
}

/** 脉搏值 → Y 坐标（160 顶 / 20 底，与体温共格） */
function yOfPulse(pulse: number): number {
  return ((PULSE_MAX - pulse) / PULSE_STEP) * GRID_Y;
}

/** 月首毫秒值（month 格式 yyyy-MM） */
function monthStart(month: string): number {
  const [year, mon] = month.split('-').map((part) => Number(part));
  return new Date(year, mon - 1, 1).getTime();
}

/** 月内天数（按当前历月真实天数） */
function daysInMonth(month: string): number {
  const [year, mon] = month.split('-').map((part) => Number(part));
  return new Date(year, mon, 0).getDate();
}

/** 时点格式化为「MM-DD HH:mm」展示段（title 与底栏共用口径） */
function formatTime(time: string): string {
  const date = new Date(time);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:00`;
}

/**
 * 组装体温单渲染模型。
 *
 * @param chart   月页出参（vitals/specialEvents/dailyValues 三段条目，vitalRef 关联体征行）
 * @param vitals  患者体征时序行（值来源：体温/脉搏数值经 vitalRef 关联补齐）
 * @param month   月页键（yyyy-MM）
 * @return 渲染模型（符号/连线/事件/网格/刻度/日行值全量描述）
 */
export function buildTempChart(
  chart: TemperatureChartVO | null,
  vitals: VitalSignVO[],
  month: string,
): TempChartModel {
  const days = daysInMonth(month);
  const startMs = monthStart(month);
  const width = AXIS_WIDTH + days * DAY_WIDTH + AXIS_WIDTH;

  // 网格：横线每 0.2℃ 一条（1℃ 粗线），竖线每 4 小时半粗（2 小时小格不画，防密度过载）+
  // 日隔粗线（§5.2：普通线 0.5px / major 1px 由视图类承载）
  const grids: ChartGridNode[] = [];
  for (let i = 0; i <= (TEMP_MAX - TEMP_MIN) / TEMP_STEP; i += 1) {
    grids.push({
      key: `h-${i}`,
      major: Number.isInteger(i * TEMP_STEP),
      pos: i * GRID_Y,
      vertical: false,
    });
  }
  for (let day = 0; day <= days; day += 1) {
    grids.push({
      key: `vd-${day}`,
      major: true,
      pos: AXIS_WIDTH + day * DAY_WIDTH,
      vertical: true,
    });
    for (let cell = 2; cell < DAY_CELLS; cell += 4) {
      grids.push({
        key: `v-${day}-${cell}`,
        major: false,
        pos: AXIS_WIDTH + day * DAY_WIDTH + cell * GRID_X,
        vertical: true,
      });
    }
  }

  // 刻度：左体温 35–42 整数度、右脉搏 20–160 每 20 一档、X 轴日号
  const tempTicks: Array<{ value: number; y: number }> = [];
  for (let t = TEMP_MAX; t >= TEMP_MIN; t -= 1) {
    tempTicks.push({ value: t, y: yOfTemp(t) });
  }
  const pulseTicks: Array<{ value: number; y: number }> = [];
  for (let p = PULSE_MAX; p >= PULSE_MIN; p -= 20) {
    pulseTicks.push({ value: p, y: yOfPulse(p) });
  }
  const dayTicks = Array.from({ length: days }, (_, i) => ({
    day: i + 1,
    x: AXIS_WIDTH + i * DAY_WIDTH + DAY_WIDTH / 2,
  }));

  // 体征值索引：vitalRef（雪花 id string 承载）→ 值行
  const vitalById = new Map(vitals.map((row) => [String(row.id ?? ''), row]));

  // 物理降温事件时点（首个降温事件后的复测体温画红圈，§5.3 S8/S9）
  const coolingTimes = (chart?.specialEvents ?? [])
    .filter((entry) => entry.specialEventType === 'PHYSICAL_COOLING' && entry.entryTime)
    .map((entry) => new Date(entry.entryTime ?? '').getTime());

  // 脉搏短绌起止窗口（时段内每脉率点画填充线，§5.6）
  const deficitWindows: Array<{ start: number; end: number }> = [];
  const deficitStarts: number[] = [];
  for (const entry of chart?.specialEvents ?? []) {
    const time = entry.entryTime ? new Date(entry.entryTime).getTime() : 0;
    if (entry.specialEventType === 'PULSE_DEFICIT_START') {
      deficitStarts.push(time);
    } else if (entry.specialEventType === 'PULSE_DEFICIT_END') {
      deficitWindows.push({ start: deficitStarts.shift() ?? 0, end: time });
    }
  }
  for (const remain of deficitStarts) {
    // 未闭合的短绌窗口：延伸到月尾（登记不展开，保守渲染）
    deficitWindows.push({ start: remain, end: Number.MAX_SAFE_INTEGER });
  }

  const symbols: ChartSymbolNode[] = [];
  const lines: ChartLineNode[] = [];

  // 体温点（按部位选符号：AXILLARY×/ORAL●/RECTAL〇）与脉率点（红点）装配
  const tempPoints: Array<{ x: number; y: number; site: string; title: string }> = [];
  const pulsePoints: Array<{ x: number; y: number; timeMs: number; title: string }> = [];
  const chartVitals = (chart?.vitals ?? [])
    .slice()
    .sort((a, b) => String(a.entryTime ?? '').localeCompare(String(b.entryTime ?? '')));
  for (const entry of chartVitals) {
    const vital = vitalById.get(String(entry.vitalRef ?? ''));
    if (vital === undefined || !entry.entryTime) {
      continue;
    }
    const x = xOf(entry.entryTime, startMs);
    const timeLabel = formatTime(entry.entryTime);
    if (vital.temperature !== undefined && vital.temperature !== null) {
      tempPoints.push({
        x,
        y: yOfTemp(vital.temperature),
        site: entry.typeKey ?? '',
        title: `${timeLabel} ${siteLabel(entry.typeKey)} ${vital.temperature}℃`,
      });
    }
    if (vital.pulse !== undefined && vital.pulse !== null) {
      pulsePoints.push({
        x,
        y: yOfPulse(vital.pulse),
        timeMs: new Date(entry.entryTime).getTime(),
        title: `${timeLabel} 脉搏 ${vital.pulse}`,
      });
    }
    // 体温脉搏同点重叠：体温符号外画红圈（§5.3 S7）
    if (
      vital.temperature !== undefined &&
      vital.temperature !== null &&
      vital.pulse !== undefined &&
      vital.pulse !== null
    ) {
      symbols.push({
        key: `overlap-${String(entry.id ?? x)}`,
        className: 'fuy-temp-overlap-ring',
        x,
        y: yOfTemp(vital.temperature),
        title: `${timeLabel} 体温与脉搏重叠`,
      });
    }
  }

  // 体温符号与连线：物理降温后首个复测点画红圈并以红虚线连降温前点（其余按部位画蓝符号）
  let cooled = false;
  let prevTemp: { x: number; y: number } | null = null;
  let prevSite: string | null = null;
  for (const point of tempPoints) {
    const symbolClass = TEMP_SITE_SYMBOL[point.site];
    const isCoolingRecheck =
      !cooled &&
      coolingTimes.length > 0 &&
      prevTemp !== null &&
      point.x > prevTemp.x &&
      coolingTimes.some((t) => xOf(new Date(t).toISOString(), startMs) <= point.x);
    if (symbolClass !== undefined) {
      symbols.push({
        key: `temp-${point.x}-${point.y}`,
        className: isCoolingRecheck ? 'fuy-temp-cooling-ring' : symbolClass,
        x: point.x,
        y: point.y,
        title: point.title,
      });
    }
    if (prevTemp !== null && point.x - prevTemp.x <= LINE_GAP_LIMIT) {
      if (isCoolingRecheck) {
        // 降温前体温 ↔ 复测红圈红虚线（仅画一次，复测后恢复常规蓝符号体系连线 §5.5-3）
        lines.push({
          key: `cooling-${prevTemp.x}-${point.x}`,
          className: 'fuy-temp-cooling-line',
          x1: prevTemp.x,
          y1: prevTemp.y,
          x2: point.x,
          y2: point.y,
        });
        cooled = true;
      } else if (prevSite === point.site) {
        // 同部位序列相邻点蓝线相连；部位切换处不连线（§5.5-1）
        lines.push({
          key: `temp-line-${prevTemp.x}-${point.x}`,
          className: 'fuy-temp-line',
          x1: prevTemp.x,
          y1: prevTemp.y,
          x2: point.x,
          y2: point.y,
        });
      }
    }
    prevTemp = { x: point.x, y: point.y };
    prevSite = point.site;
  }

  // 脉率红点 + 红线相连；短绌窗口内每脉率点画填充线（§5.5-2 / §5.6）
  let prevPulse: { x: number; y: number } | null = null;
  for (const point of pulsePoints) {
    symbols.push({
      key: `pulse-${point.x}-${point.y}`,
      className: 'fuy-pulse-dot',
      x: point.x,
      y: point.y,
      title: point.title,
    });
    if (prevPulse !== null && point.x - prevPulse.x <= LINE_GAP_LIMIT) {
      lines.push({
        key: `pulse-line-${prevPulse.x}-${point.x}`,
        className: 'fuy-pulse-line',
        x1: prevPulse.x,
        y1: prevPulse.y,
        x2: point.x,
        y2: point.y,
      });
    }
    if (deficitWindows.some((w) => point.timeMs >= w.start && point.timeMs <= w.end)) {
      lines.push({
        key: `deficit-${point.x}`,
        className: 'fuy-temp-deficit-line',
        x1: point.x,
        y1: point.y,
        x2: point.x,
        y2: Math.max(0, point.y - DEFICIT_SPAN),
      });
    }
    prevPulse = { x: point.x, y: point.y };
  }

  // 特殊事件竖线：贯穿曲线区（§5.6），呼吸心跳停止双竖线
  const events: ChartEventNode[] = [];
  for (const entry of chart?.specialEvents ?? []) {
    const type = entry.specialEventType ?? '';
    const modifier = EVENT_MODIFIERS[type];
    if (modifier === undefined || !entry.entryTime) {
      continue;
    }
    events.push({
      key: `event-${String(entry.id ?? type)}-${String(entry.entryTime)}`,
      className: `fuy-event-line ${modifier}`,
      x: xOf(entry.entryTime, startMs),
      label: EVENT_LABELS[type] ?? type,
      double: type === 'CARDIAC_ARREST',
    });
  }

  // 日行值底栏格：出入量小结文本（班次小结/24h 总结挂红双线 §5.7）
  const dailyCells: DailyCell[] = [];
  for (const entry of chart?.dailyValues ?? []) {
    if (!entry.entryTime) {
      continue;
    }
    const day = Math.floor((new Date(entry.entryTime).getTime() - startMs) / 86400000) + 1;
    if (day < 1 || day > days) {
      continue;
    }
    const rowKey = DAILY_ROW_OF_TYPE[entry.dailyValueType ?? ''];
    if (rowKey === undefined || !entry.valueText) {
      continue;
    }
    dailyCells.push({
      day,
      rowKey,
      valueText: entry.valueText,
      ruleClass:
        entry.dailyValueType === 'IO_SUMMARY_24H'
          ? 'fuy-io-summary-rule--24h'
          : 'fuy-io-summary-rule--shift',
    });
  }

  return {
    days,
    width,
    height: CHART_HEIGHT,
    symbols,
    lines,
    events,
    grids,
    tempTicks,
    pulseTicks,
    dayTicks,
    dailyCells,
  };
}

/** 部位中文读法（title 无障碍文案用） */
function siteLabel(site: string | undefined): string {
  if (site === 'AXILLARY') {
    return '腋温';
  }
  if (site === 'ORAL') {
    return '口温';
  }
  if (site === 'RECTAL') {
    return '肛温';
  }
  return '体温';
}
