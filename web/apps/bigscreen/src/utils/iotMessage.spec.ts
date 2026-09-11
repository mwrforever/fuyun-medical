// 遥测摘要载荷解析单测（BRIEF-PR5-01 §2.3）：合法载荷逐字段断言、边界（空 items 放行）与
// 异常（缺字段/字段类型不符/残缺元素/嵌套脏数据）三类场景齐备；纯函数直测零 mock 零网络。
import { describe, expect, it } from 'vitest';
import { parseTelemetrySummary } from './iotMessage';

/** 构造合法载荷（字段名与后端 ITelemetryPushService.TelemetrySummary record 逐字对齐） */
function validPayload(): Record<string, unknown> {
  return {
    count: 3,
    occurredAtUpperBound: '2026-09-11T02:00:00Z',
    items: [
      { deviceId: 'dev-001', metricCode: 'heartRate' },
      { deviceId: 'dev-002', metricCode: 'spo2' },
    ],
  };
}

describe('遥测摘要载荷解析', () => {
  it('合法载荷解析成功且字段逐一保留', () => {
    const summary = parseTelemetrySummary(validPayload());
    expect(summary).not.toBeNull();
    expect(summary?.count).toBe(3);
    // occurredAtUpperBound 原样承载 ISO-8601 字符串（禁前端换算丢失原始精度）
    expect(summary?.occurredAtUpperBound).toBe('2026-09-11T02:00:00Z');
    expect(summary?.items).toHaveLength(2);
    expect(summary?.items[0]).toEqual({ deviceId: 'dev-001', metricCode: 'heartRate' });
    expect(summary?.items[1]?.metricCode).toBe('spo2');
  });

  it('items 为空数组时放行（后端空批次不推送，解析侧防御口径仍收窄放行）', () => {
    const payload = validPayload();
    payload['items'] = [];
    const summary = parseTelemetrySummary(payload);
    expect(summary).not.toBeNull();
    expect(summary?.items).toHaveLength(0);
  });

  it('缺失 count 字段返回 null', () => {
    const payload = validPayload();
    delete payload['count'];
    expect(parseTelemetrySummary(payload)).toBeNull();
  });

  it('count 非正数（零与负数）返回 null', () => {
    const zero = validPayload();
    zero['count'] = 0;
    const negative = validPayload();
    negative['count'] = -1;
    expect(parseTelemetrySummary(zero)).toBeNull();
    expect(parseTelemetrySummary(negative)).toBeNull();
  });

  it('occurredAtUpperBound 非字符串返回 null', () => {
    const payload = validPayload();
    payload['occurredAtUpperBound'] = 12345;
    expect(parseTelemetrySummary(payload)).toBeNull();
  });

  it('occurredAtUpperBound 为空字符串返回 null', () => {
    const payload = validPayload();
    payload['occurredAtUpperBound'] = '';
    expect(parseTelemetrySummary(payload)).toBeNull();
  });

  it('items 含残缺元素（缺 metricCode）返回 null', () => {
    const payload = validPayload();
    payload['items'] = [{ deviceId: 'dev-001' }];
    expect(parseTelemetrySummary(payload)).toBeNull();
  });

  it('items 含空字符串 deviceId 的元素返回 null', () => {
    const payload = validPayload();
    payload['items'] = [{ deviceId: '', metricCode: 'heartRate' }];
    expect(parseTelemetrySummary(payload)).toBeNull();
  });

  it('items 非数组（嵌套脏数据）返回 null', () => {
    const payload = validPayload();
    payload['items'] = { deviceId: 'dev-001', metricCode: 'heartRate' };
    expect(parseTelemetrySummary(payload)).toBeNull();
  });

  it('载荷为非对象（null/原始值）返回 null', () => {
    expect(parseTelemetrySummary(null)).toBeNull();
    expect(parseTelemetrySummary('frame')).toBeNull();
    expect(parseTelemetrySummary(42)).toBeNull();
    expect(parseTelemetrySummary(undefined)).toBeNull();
  });
});
