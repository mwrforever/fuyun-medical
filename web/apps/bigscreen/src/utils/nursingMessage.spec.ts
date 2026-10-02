// 护士站大屏 board 帧语义解析单测（Task 17，镜像 iotMessage.spec 范式）：统一信封
// {type, payload, occurredAt} 五类型词表逐字段收窄——合法载荷逐字段断言、边界（bedNo null/
// 空串双形态放行、bedId/callNo 可空面）与异常（缺字段/词表外 type/字段类型不符/清单内残缺
// 元素）三类场景齐备；纯函数直测零 mock 零网络。字段名与后端 NurseBoardPushFrame record
// 逐字对齐（Long 字段经 Jackson 全局字符串化，前端 string 承载——web A.3-6）。
import { describe, expect, it } from 'vitest';
import { parseNursingBoardFrame } from './nursingMessage';

/** 构造合法信封（type/payload 可覆写；occurredAt 为 Instant 的 ISO-8601 字符串线格式） */
function envelope(type: string, payload: unknown): unknown {
  return { type, payload, occurredAt: '2026-10-03T06:00:00Z' };
}

describe('护理 board 帧信封收窄', () => {
  it('非对象载荷返回 null', () => {
    expect(parseNursingBoardFrame(null)).toBeNull();
    expect(parseNursingBoardFrame('BED_PATIENT')).toBeNull();
    expect(parseNursingBoardFrame(42)).toBeNull();
  });

  it('type 缺失/非字符串返回 null', () => {
    expect(parseNursingBoardFrame({ payload: {}, occurredAt: '2026-10-03T06:00:00Z' })).toBeNull();
    expect(
      parseNursingBoardFrame({ type: 7, payload: {}, occurredAt: '2026-10-03T06:00:00Z' }),
    ).toBeNull();
  });

  it('词表外 type 返回 null（五值冻结词表禁自增值）', () => {
    expect(parseNursingBoardFrame(envelope('UNKNOWN_TYPE', {}))).toBeNull();
    expect(parseNursingBoardFrame(envelope('', {}))).toBeNull();
  });

  it('occurredAt 缺失/空串/非字符串返回 null', () => {
    expect(parseNursingBoardFrame({ type: 'BED_PATIENT', payload: {} })).toBeNull();
    expect(parseNursingBoardFrame({ type: 'BED_PATIENT', payload: {}, occurredAt: '' })).toBeNull();
    expect(
      parseNursingBoardFrame({ type: 'BED_PATIENT', payload: {}, occurredAt: 123 }),
    ).toBeNull();
  });

  it('payload 缺失/非对象返回 null', () => {
    expect(parseNursingBoardFrame({ type: 'BED_PATIENT', occurredAt: 't' })).toBeNull();
    expect(
      parseNursingBoardFrame({ type: 'BED_PATIENT', payload: 'text', occurredAt: 't' }),
    ).toBeNull();
  });

  it('合法类型但载荷不合法返回 null（载荷收窄挂接在信封内非独立出口）', () => {
    expect(parseNursingBoardFrame(envelope('TASK_OVERDUE', { taskNo: 'TK1' }))).toBeNull();
  });
});

describe('床位患者动态帧（BED_PATIENT）载荷收窄', () => {
  function bedPayload(bedNo: unknown): unknown {
    return { visitId: 'I20260920001', patientId: '1002', bedNo, wardId: '1001' };
  }

  it('合法载荷解析成功且字段逐一保留（bedNo 空串形态放行——入科空占位）', () => {
    const frame = parseNursingBoardFrame(envelope('BED_PATIENT', bedPayload('')));
    expect(frame).not.toBeNull();
    expect(frame?.type).toBe('BED_PATIENT');
    expect(frame?.occurredAt).toBe('2026-10-03T06:00:00Z');
    if (frame?.type === 'BED_PATIENT') {
      expect(frame.payload).toEqual({
        visitId: 'I20260920001',
        patientId: '1002',
        bedNo: '',
        wardId: '1001',
      });
    }
  });

  it('bedNo null 形态放行（实现传 null，javadoc 空串措辞失准——双形态防御）', () => {
    const frame = parseNursingBoardFrame(envelope('BED_PATIENT', bedPayload(null)));
    expect(frame).not.toBeNull();
    if (frame?.type === 'BED_PATIENT') {
      expect(frame.payload.bedNo).toBeNull();
    }
  });

  it('bedNo 非字符串非 null（数字形态）归一为 null 放行', () => {
    const frame = parseNursingBoardFrame(envelope('BED_PATIENT', bedPayload(12)));
    expect(frame).not.toBeNull();
    if (frame?.type === 'BED_PATIENT') {
      expect(frame.payload.bedNo).toBeNull();
    }
  });

  it('visitId/patientId/wardId 任一缺失或非字符串返回 null（patientId 线格式为字符串化 Long）', () => {
    expect(parseNursingBoardFrame(envelope('BED_PATIENT', bedPayload('12')))).not.toBeNull();
    const noVisit = bedPayload('12');
    delete (noVisit as Record<string, unknown>)['visitId'];
    expect(parseNursingBoardFrame(envelope('BED_PATIENT', noVisit))).toBeNull();
    const numericPatient = bedPayload('12');
    (numericPatient as Record<string, unknown>)['patientId'] = 1002;
    expect(parseNursingBoardFrame(envelope('BED_PATIENT', numericPatient))).toBeNull();
  });
});

describe('任务逾期帧（TASK_OVERDUE）载荷收窄', () => {
  function overduePayload(): Record<string, unknown> {
    return {
      taskNo: 'TK2026100300001',
      taskType: 'TURN',
      planTime: '2026-10-03T05:30:00+08:00',
      escalationCount: 2,
      wardId: '1001',
    };
  }

  it('合法载荷解析成功且字段逐一保留（escalationCount 数字承载）', () => {
    const frame = parseNursingBoardFrame(envelope('TASK_OVERDUE', overduePayload()));
    expect(frame?.type).toBe('TASK_OVERDUE');
    if (frame?.type === 'TASK_OVERDUE') {
      expect(frame.payload).toEqual(overduePayload());
    }
  });

  it('escalationCount 非正整数（零/负数/小数/字符串）返回 null', () => {
    for (const bad of [0, -1, 1.5, '2']) {
      const payload = overduePayload();
      payload['escalationCount'] = bad;
      expect(parseNursingBoardFrame(envelope('TASK_OVERDUE', payload))).toBeNull();
    }
  });

  it('taskNo/taskType/planTime/wardId 任一缺失返回 null', () => {
    const payload = overduePayload();
    delete payload['planTime'];
    expect(parseNursingBoardFrame(envelope('TASK_OVERDUE', payload))).toBeNull();
  });
});

describe('输注升级帧（INFUSION_ESCALATION）载荷收窄', () => {
  function escalationPayload(): Record<string, unknown> {
    return {
      alarmNo: 'AL20261003001',
      executionNos: ['EX20261003001', 'EX20261003002'],
      escalatedCount: 2,
      taskEscalatedCount: 1,
    };
  }

  it('合法载荷解析成功（executionNos 跨患者多执行单号清单整体承载）', () => {
    const frame = parseNursingBoardFrame(envelope('INFUSION_ESCALATION', escalationPayload()));
    expect(frame?.type).toBe('INFUSION_ESCALATION');
    if (frame?.type === 'INFUSION_ESCALATION') {
      expect(frame.payload.executionNos).toEqual(['EX20261003001', 'EX20261003002']);
      expect(frame.payload.escalatedCount).toBe(2);
      expect(frame.payload.taskEscalatedCount).toBe(1);
    }
  });

  it('executionNos 非数组或清单内残缺元素（非字符串/空串）返回 null', () => {
    const notArray = escalationPayload();
    notArray['executionNos'] = 'EX1';
    expect(parseNursingBoardFrame(envelope('INFUSION_ESCALATION', notArray))).toBeNull();
    const dirtyElement = escalationPayload();
    dirtyElement['executionNos'] = ['EX1', 42];
    expect(parseNursingBoardFrame(envelope('INFUSION_ESCALATION', dirtyElement))).toBeNull();
  });

  it('escalatedCount/taskEscalatedCount 非负整数校验（负数/字符串拒绝，零放行）', () => {
    const zeroTask = escalationPayload();
    zeroTask['taskEscalatedCount'] = 0;
    expect(parseNursingBoardFrame(envelope('INFUSION_ESCALATION', zeroTask))).not.toBeNull();
    const negative = escalationPayload();
    negative['escalatedCount'] = -1;
    expect(parseNursingBoardFrame(envelope('INFUSION_ESCALATION', negative))).toBeNull();
    const stringCount = escalationPayload();
    stringCount['escalatedCount'] = '2';
    expect(parseNursingBoardFrame(envelope('INFUSION_ESCALATION', stringCount))).toBeNull();
  });
});

describe('不良事件超时提醒帧（ADVERSE_EVENT_REMIND）载荷收窄', () => {
  function remindPayload(): Record<string, unknown> {
    return { wardId: '1001', overdueCount: 3, sampleEventNos: ['AE1', 'AE2', 'AE3'] };
  }

  it('合法载荷解析成功（样例事件号清单有界 5 条承载）', () => {
    const frame = parseNursingBoardFrame(envelope('ADVERSE_EVENT_REMIND', remindPayload()));
    expect(frame?.type).toBe('ADVERSE_EVENT_REMIND');
  });

  it('overdueCount 非负整数校验（零放行、负数/小数拒绝）', () => {
    const zero = remindPayload();
    zero['overdueCount'] = 0;
    expect(parseNursingBoardFrame(envelope('ADVERSE_EVENT_REMIND', zero))).not.toBeNull();
    const negative = remindPayload();
    negative['overdueCount'] = -2;
    expect(parseNursingBoardFrame(envelope('ADVERSE_EVENT_REMIND', negative))).toBeNull();
    const fraction = remindPayload();
    fraction['overdueCount'] = 1.5;
    expect(parseNursingBoardFrame(envelope('ADVERSE_EVENT_REMIND', fraction))).toBeNull();
  });

  it('sampleEventNos 非数组或残缺元素返回 null（空清单防御放行）', () => {
    const empty = remindPayload();
    empty['sampleEventNos'] = [];
    expect(parseNursingBoardFrame(envelope('ADVERSE_EVENT_REMIND', empty))).not.toBeNull();
    const notArray = remindPayload();
    notArray['sampleEventNos'] = 'AE1';
    expect(parseNursingBoardFrame(envelope('ADVERSE_EVENT_REMIND', notArray))).toBeNull();
  });
});

describe('设备呼叫转发帧（CALL_TRIGGERED）载荷收窄', () => {
  function callPayload(): Record<string, unknown> {
    return {
      callNo: 'CALL2026100300001',
      deviceId: 'dev-call-01',
      callType: 'NURSE_CALL',
      bedId: '12',
      wardId: '1001',
      triggeredAt: '2026-10-03T05:58:00Z',
    };
  }

  it('合法载荷解析成功（bedId/wardId 字符串化 Long 承载）', () => {
    const frame = parseNursingBoardFrame(envelope('CALL_TRIGGERED', callPayload()));
    expect(frame?.type).toBe('CALL_TRIGGERED');
    if (frame?.type === 'CALL_TRIGGERED') {
      expect(frame.payload).toEqual(callPayload());
    }
  });

  it('bedId null/缺失归一为 null 放行（IoTDA 直发路径床号可缺）', () => {
    const nullBed = callPayload();
    nullBed['bedId'] = null;
    const frame = parseNursingBoardFrame(envelope('CALL_TRIGGERED', nullBed));
    expect(frame).not.toBeNull();
    if (frame?.type === 'CALL_TRIGGERED') {
      expect(frame.payload.bedId).toBeNull();
    }
  });

  it('callNo/deviceId/callType/wardId/triggeredAt 任一缺失或非字符串返回 null', () => {
    const payload = callPayload();
    delete payload['triggeredAt'];
    expect(parseNursingBoardFrame(envelope('CALL_TRIGGERED', payload))).toBeNull();
  });
});
