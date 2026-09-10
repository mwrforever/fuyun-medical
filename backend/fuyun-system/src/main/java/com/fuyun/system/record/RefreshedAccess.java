package com.fuyun.system.record;

/**
 * 刷新换发结果载体（B3.2 刷新端点，ITokenService.refreshAccessToken 产物）。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变）。同 sid 换发语义：新 access 与
 * 原 refresh 共享同一会话键，refresh 值不轮换（P0 口径，BRIEF-PR3-01 §1.2）——调用方以
 * 请求中的原 refreshToken 原样回填响应，本对象只承载换发产物与会话状态。
 *
 * @param accessToken 换发的新访问令牌（typ=access，同原 refresh 的 sid），非空
 * @param session     会话状态（refresh 校验链通过后的还原值，含角色摘要），非空；
 *                    校验成功时滑动续期已生效
 */
public record RefreshedAccess(String accessToken, SessionData session) {}
