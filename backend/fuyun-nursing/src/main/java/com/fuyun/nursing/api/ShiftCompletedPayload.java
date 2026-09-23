package com.fuyun.nursing.api;

/**
 * 交接班完成事件载荷（nursing.shift.completed，V800 id 60 冻结契约，Task 9 发布）：
 * 交接班单确认完成时发布，M19 工作量统计取数。
 *
 * @param handoverNo       交接班单业务号，非空；来源：交接班发号器
 * @param wardId           病区标识，非空；来源：交接班所属病区
 * @param shiftCode        班次编码（白班/小夜/大夜等三班制班次），非空
 * @param outgoingNurseId  交班护士工号，非空；来源：交接班单交班方
 * @param incomingNurseId  接班护士工号，非空；来源：交接班单接班方
 */
public record ShiftCompletedPayload(
        String handoverNo, String wardId, String shiftCode, String outgoingNurseId, String incomingNurseId) {}
