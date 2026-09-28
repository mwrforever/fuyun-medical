package com.fuyun.ward.vo;

import com.fuyun.ward.enums.CallType;
import java.util.List;

/**
 * 呼叫路由解析视图（POST /api/v1/ward/ward-calls/{callNo}/route 出网载体）：规则解析目标链与
 * 任务转换开关快照（M05 任务创建 PR-3 闭合，本 PR 出开关值供前端注记）。
 *
 * @param callNo          呼叫业务号，非空
 * @param wardId          病区 ID，非空
 * @param callType        呼叫类型，非空
 * @param targetChain     目标链（按序转接目标，规则 target_chain JSONB 解析结果），非空
 * @param taskConvertFlag 任务转换开关（true 且 EMERGENCY 类时转接触发 M05 任务创建——PR-3），非空
 */
public record WardCallRouteVO(
        String callNo, Long wardId, CallType callType, List<String> targetChain, boolean taskConvertFlag) {}
