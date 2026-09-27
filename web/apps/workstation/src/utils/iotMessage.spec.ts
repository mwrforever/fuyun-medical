// 遥测摘要与告警帧载荷解析单测：逐字段收窄守卫（unknown → 契约类型，禁 any 口径）——
// 合法载荷放行、任一字段残缺整帧拒绝（返回 null）、可空字段（患者/就诊）null 放行。
import { describe, expect, it } from 'vitest';
import { parseAlarmPayload, parseTelemetrySummary } from './iotMessage';

describe('遥测摘要帧载荷解析', () => {
  it('合法摘要载荷放行（count/items/occurredAtUpperBound 齐全）', () => {
    const parsed = parseTelemetrySummary({
      count: 2,
      occurredAtUpperBound: '2026-09-26T02:00:00Z',
      items: [{ deviceId: 'dev-1', metricCode: 'INFUSION_SHORTAGE' }],
    });
    expect(parsed).not.toBeNull();
    expect(parsed?.count).toBe(2);
    expect(parsed?.items).toHaveLength(1);
  });

  it('空 items 数组放行（防御口径：后端空批次不推送但残帧不炸订阅）', () => {
    const parsed = parseTelemetrySummary({
      count: 1,
      occurredAtUpperBound: '2026-09-26T02:00:00Z',
      items: [],
    });
    expect(parsed).not.toBeNull();
    expect(parsed?.items).toEqual([]);
  });

  it('残缺载荷整帧拒绝：count 非正整数/items 元素缺 metricCode/非对象均返回 null', () => {
    expect(parseTelemetrySummary(null)).toBeNull();
    expect(
      parseTelemetrySummary({ count: 0, occurredAtUpperBound: '2026-09-26T02:00:00Z', items: [] }),
    ).toBeNull();
    expect(parseTelemetrySummary({ count: 1, occurredAtUpperBound: '', items: [] })).toBeNull();
    expect(
      parseTelemetrySummary({ count: 1, occurredAtUpperBound: '2026-09-26T02:00:00Z' }),
    ).toBeNull();
    expect(
      parseTelemetrySummary({
        count: 1,
        occurredAtUpperBound: '2026-09-26T02:00:00Z',
        items: [{ deviceId: 'dev-1' }],
      }),
    ).toBeNull();
  });
});

describe('告警帧载荷解析', () => {
  it('合法告警载荷放行（可空患者/就诊为 null 时仍放行）', () => {
    const parsed = parseAlarmPayload({
      alarmNo: 'AL20260926001',
      deviceId: 'dev-infusion-1',
      patientId: null,
      visitId: null,
      wardId: '1001',
      alarmLevel: 'CRITICAL',
      metricCode: 'INFUSION_SHORTAGE',
      triggerValue: '4.5',
      ruleId: '901',
      occurredAt: '2026-09-26T02:00:00Z',
    });
    expect(parsed).not.toBeNull();
    expect(parsed?.alarmNo).toBe('AL20260926001');
    expect(parsed?.patientId).toBeNull();
  });

  it('残缺告警载荷整帧拒绝：必填字段缺失/空串/非对象均返回 null', () => {
    expect(parseAlarmPayload(null)).toBeNull();
    expect(
      parseAlarmPayload({
        deviceId: 'dev-1',
        wardId: '1001',
        alarmLevel: 'CRITICAL',
        metricCode: 'INFUSION_SHORTAGE',
        triggerValue: '4.5',
        ruleId: '901',
        occurredAt: '2026-09-26T02:00:00Z',
      }),
    ).toBeNull();
    expect(
      parseAlarmPayload({
        alarmNo: '',
        deviceId: 'dev-1',
        wardId: '1001',
        alarmLevel: 'CRITICAL',
        metricCode: 'INFUSION_SHORTAGE',
        triggerValue: '4.5',
        ruleId: '901',
        occurredAt: '2026-09-26T02:00:00Z',
      }),
    ).toBeNull();
    expect(
      parseAlarmPayload({
        alarmNo: 'AL1',
        deviceId: 'dev-1',
        patientId: '1932',
        wardId: 1001,
        alarmLevel: 'CRITICAL',
        metricCode: 'INFUSION_SHORTAGE',
        triggerValue: '4.5',
        ruleId: '901',
        occurredAt: '2026-09-26T02:00:00Z',
      }),
    ).toBeNull();
  });
});
