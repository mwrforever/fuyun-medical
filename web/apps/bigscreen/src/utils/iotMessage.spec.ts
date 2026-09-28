// 遥测摘要载荷解析单测（BRIEF-PR5-01 §2.3）：合法载荷逐字段断言、边界（空 items 放行）与
// 异常（缺字段/字段类型不符/残缺元素/嵌套脏数据）三类场景齐备；纯函数直测零 mock 零网络。
// P2 PR-2 扩展：告警触发帧（parseIotAlarmFrame）与全院摘要帧（parseDashboardSummary）同范式覆盖。
import { describe, expect, it } from 'vitest';
import { parseDashboardSummary, parseIotAlarmFrame, parseTelemetrySummary } from './iotMessage';

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

/** 构造合法告警触发帧（字段名与后端 AlarmTriggeredPayload record 逐字对齐，Long 已字符串化） */
function validAlarmPayload(): Record<string, unknown> {
  return {
    alarmNo: 'AL20260926001',
    deviceId: 'dev-icu-01',
    patientId: '1002',
    visitId: 'I20260920001',
    wardId: '1001',
    alarmLevel: 'CRITICAL',
    metricCode: 'MDC_ECG_HEART_RATE',
    triggerValue: '152',
    ruleId: '3001',
    occurredAt: '2026-09-26T02:00:00Z',
  };
}

describe('告警触发帧载荷解析', () => {
  it('合法载荷解析成功且字段逐一保留（含可空字段）', () => {
    const frame = parseIotAlarmFrame(validAlarmPayload());
    expect(frame).not.toBeNull();
    expect(frame?.alarmNo).toBe('AL20260926001');
    expect(frame?.deviceId).toBe('dev-icu-01');
    expect(frame?.patientId).toBe('1002');
    expect(frame?.visitId).toBe('I20260920001');
    expect(frame?.wardId).toBe('1001');
    expect(frame?.alarmLevel).toBe('CRITICAL');
    expect(frame?.metricCode).toBe('MDC_ECG_HEART_RATE');
    expect(frame?.triggerValue).toBe('152');
    expect(frame?.ruleId).toBe('3001');
    // occurredAt 原样承载 ISO-8601 字符串（禁前端换算丢失原始精度）
    expect(frame?.occurredAt).toBe('2026-09-26T02:00:00Z');
  });

  it('公共区域告警（patientId/visitId 为 null 或缺省）解析成功且归一为 null', () => {
    const withNull = validAlarmPayload();
    withNull['patientId'] = null;
    withNull['visitId'] = null;
    const frame = parseIotAlarmFrame(withNull);
    expect(frame).not.toBeNull();
    expect(frame?.patientId).toBeNull();
    expect(frame?.visitId).toBeNull();
    // 缺省（后端 null 字段可能缺省序列化）同样归一为 null
    const withoutFields = validAlarmPayload();
    delete withoutFields['patientId'];
    delete withoutFields['visitId'];
    const frameMissing = parseIotAlarmFrame(withoutFields);
    expect(frameMissing?.patientId).toBeNull();
    expect(frameMissing?.visitId).toBeNull();
  });

  it('必填字段缺失或非字符串返回 null（alarmNo/wardId/triggerValue/occurredAt 抽样）', () => {
    const noAlarmNo = validAlarmPayload();
    delete noAlarmNo['alarmNo'];
    const emptyWard = validAlarmPayload();
    emptyWard['wardId'] = '';
    const numericTrigger = validAlarmPayload();
    numericTrigger['triggerValue'] = 152; // 触发值为文本形态（保留原始形态），数值形态视为脏帧
    const noOccurredAt = validAlarmPayload();
    delete noOccurredAt['occurredAt'];
    expect(parseIotAlarmFrame(noAlarmNo)).toBeNull();
    expect(parseIotAlarmFrame(emptyWard)).toBeNull();
    expect(parseIotAlarmFrame(numericTrigger)).toBeNull();
    expect(parseIotAlarmFrame(noOccurredAt)).toBeNull();
  });

  it('载荷为非对象（null/原始值）返回 null', () => {
    expect(parseIotAlarmFrame(null)).toBeNull();
    expect(parseIotAlarmFrame('frame')).toBeNull();
    expect(parseIotAlarmFrame(undefined)).toBeNull();
  });
});

/** 构造合法全院摘要帧（与 REST DashboardSummaryVO 同构：计数 long→string、估计值为数值） */
function validSummaryPayload(): Record<string, unknown> {
  return {
    deviceTotal: '15',
    onlineCount: '12',
    offlineCount: '3',
    activeAlarmCount: '4',
    stormActive: false,
    backlogEstimate: 0,
    qualityScore: 98.5,
  };
}

describe('全院摘要帧载荷解析', () => {
  it('合法载荷解析成功且字段逐一保留', () => {
    const summary = parseDashboardSummary(validSummaryPayload());
    expect(summary).not.toBeNull();
    expect(summary?.deviceTotal).toBe('15');
    expect(summary?.onlineCount).toBe('12');
    expect(summary?.offlineCount).toBe('3');
    expect(summary?.activeAlarmCount).toBe('4');
    expect(summary?.stormActive).toBe(false);
    expect(summary?.backlogEstimate).toBe(0);
    expect(summary?.qualityScore).toBe(98.5);
  });

  it('风暴态为布尔翻转值时解析成功（true 形态）', () => {
    const storming = validSummaryPayload();
    storming['stormActive'] = true;
    expect(parseDashboardSummary(storming)?.stormActive).toBe(true);
  });

  it('任一字段缺失或类型不符返回 null（计数数值化/风暴态字符串化/质量分缺失抽样）', () => {
    const numericCount = validSummaryPayload();
    numericCount['onlineCount'] = 12; // 后端 long 经 Jackson 字符串化，数值形态视为脏帧
    const stringStorm = validSummaryPayload();
    stringStorm['stormActive'] = 'false';
    const noScore = validSummaryPayload();
    delete noScore['qualityScore'];
    expect(parseDashboardSummary(numericCount)).toBeNull();
    expect(parseDashboardSummary(stringStorm)).toBeNull();
    expect(parseDashboardSummary(noScore)).toBeNull();
  });

  it('载荷为非对象（null/原始值）返回 null', () => {
    expect(parseDashboardSummary(null)).toBeNull();
    expect(parseDashboardSummary(42)).toBeNull();
    expect(parseDashboardSummary(undefined)).toBeNull();
  });
});
