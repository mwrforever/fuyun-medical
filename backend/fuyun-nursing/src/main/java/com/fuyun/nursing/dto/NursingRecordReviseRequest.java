package com.fuyun.nursing.dto;

/**
 * 护理记录修订入参（POST /api/v1/nursing/nursing-records/{recordNo}/revise）。修订语义为
 * 「整篇替换正文」：服务端插入新行（新号 + status=REVISED + revised_from=原号），原行保留
 * SUBMITTED 态零改动（GC25 修订留痕原值可见）；修订件业务时间取服务器时间（GC25）。
 *
 * @param observation 修订后病情观察（结构化段），可空；来源：操作者录入
 * @param measures    修订后护理措施（结构化段），可空；来源：操作者录入
 * @param evaluation  修订后效果评价（结构化段），可空；来源：操作者录入
 * @param freeText    修订后自由文本补充，可空；来源：操作者录入
 */
public record NursingRecordReviseRequest(String observation, String measures, String evaluation, String freeText) {}
