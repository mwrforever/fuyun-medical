// 工作台展示纯函数单测（册 2 审查修复波 2 随 useWorkbenchFeed → utils 迁移同批落位）：
// 叫号帧载荷收窄（患者姓名/医生标识字段即弃——脱敏红线直测）、计数千分位格式化（脏数据
// 不造数出 —）与趋势周同比推导（分母为零诚实缺示）。纯函数直测，无 DOM 无出网无模块态。
import { describe, expect, it } from 'vitest';
import { formatCount, parseQueueCalledFrame, weekOverWeekPercent } from './workbenchDisplay';

describe('workbenchDisplay 展示纯函数（自 useWorkbenchFeed 迁入 utils）', () => {
  it('formatCount 千分位格式化：合法数字串按 zh-CN 千分位出，缺失/脏数据诚实出 —', () => {
    expect(formatCount('1284')).toBe('1,284');
    expect(formatCount('0')).toBe('0');
    expect(formatCount('37')).toBe('37');
    // 缺失与非数字脏数据一律 — 占位（零伪数据：不渲染 0 也不渲染 NaN）
    expect(formatCount(undefined)).toBe('—');
    expect(formatCount('')).toBe('—');
    expect(formatCount('not-a-number')).toBe('—');
  });

  it('weekOverWeekPercent 同比推导：两组真实和值之差比上周（一位小数带符号），分母为零诚实缺示', () => {
    // 近 7 日和 148 vs 前 7 日和 70 → +111.4（与 HomeView 夹具周同比断言同口径）
    expect(weekOverWeekPercent([20, 20, 20, 20, 20, 20, 28], [10, 10, 10, 10, 10, 10, 10])).toBe(
      111.4,
    );
    expect(weekOverWeekPercent([7], [10])).toBe(-30);
    // 上周和值为 0：分母为零返回 null（不造数，视图渲染 —）
    expect(weekOverWeekPercent([1, 2, 3], [0, 0, 0])).toBeNull();
  });

  it('parseQueueCalledFrame 载荷收窄：合法帧取三字段放行，患者姓名/医生标识存在即弃不外泄', () => {
    const frame = parseQueueCalledFrame({
      type: 'CALLED',
      ticketNo: 'T090',
      patientName: '张三',
      doctorId: 'D9',
      room: '3',
    });
    // 收窄后仅剩渲染所需三字段：姓名/医生标识不进入返回对象（脱敏从严不止不渲染）
    expect(frame).toEqual({ type: 'CALLED', ticketNo: 'T090', room: '3' });
    expect(frame && 'patientName' in frame).toBe(false);
    expect(frame && 'doctorId' in frame).toBe(false);
  });

  it('parseQueueCalledFrame 非法载荷拒绝：非对象/type 或票号缺失返回 null，room 允许缺失补空串', () => {
    expect(parseQueueCalledFrame(null)).toBeNull();
    expect(parseQueueCalledFrame('frame')).toBeNull();
    expect(parseQueueCalledFrame({ ticketNo: 'T090' })).toBeNull();
    expect(parseQueueCalledFrame({ type: 'CALLED', ticketNo: '' })).toBeNull();
    // room 缺失为合法形态（收窄后补空串，由消费方落 null）
    expect(parseQueueCalledFrame({ type: 'CALLED', ticketNo: 'T091' })).toEqual({
      type: 'CALLED',
      ticketNo: 'T091',
      room: '',
    });
  });
});
