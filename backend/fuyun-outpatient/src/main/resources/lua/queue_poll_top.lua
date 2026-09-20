-- 候诊队列原子出队守卫（Task 7 叫号路径）：目标成员仍在位（ZSCORE 命中）才 ZREM——并发双医生
-- 同时叫号时仅一方出队成功（另一方返回 0 继续扫描下一位），杜绝同票双叫。
-- KEYS[1] = fy:outpatient:queue:{deptCode}（A.5-1 键规范，{deptCode} 兼作 Redis Cluster hash tag）
-- ARGV[1] = 票据主键 member（String.valueOf(ticketPk)）
-- 返回：1=出队成功；0=成员已被并发取走或不存在
local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not score then
    return 0
end
return redis.call('ZREM', KEYS[1], ARGV[1])
