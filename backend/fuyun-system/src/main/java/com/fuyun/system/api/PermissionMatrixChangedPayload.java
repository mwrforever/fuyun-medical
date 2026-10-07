package com.fuyun.system.api;

/**
 * 权限矩阵变更广播事件载荷（system.permission.changed，V1120 id 84 登记行，PR-4F W-96②）。
 *
 * <p>触发场景：角色权限绑定全量覆写/角色启停后的事务提交广播。消费动作与生效语义（F4）：
 * 各实例经广播队列重载 PermissionRegistry（403 鉴权面实时生效）；治理命名队列单实例消费
 * 承担幂等三步 + 受影响角色会话键清理（踢出重登，permissions 快照随重登刷新）。事件对象
 * 定义在发布方 api 包（backend 宪法 B.3-1），作为信封 payload 进线格式。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变）。
 *
 * @param roleCode 发生矩阵变更的角色编码，非空；来源：发布方写事务内的路径参数
 */
public record PermissionMatrixChangedPayload(String roleCode) {}
