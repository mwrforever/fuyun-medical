-- 号源池原子预扣脚本（M03 Spec 3.2 双道闸第一道；Redis 异常由调用方降级直连 DB 条件更新）：
-- KEYS[1]=池键 fy:outpatient:pool:{poolId}（{poolId} 为 Redis Cluster hash tag，A.5-17）；
-- ARGV[1]=扣减量（单次预约恒为 1）；ARGV[2]=池总量（与 pool_release.lua 签名对称的预留位）；
-- ARGV[3]=TTL 秒（sched_date 次日 02:00 对账窗口缓冲）。
-- 返回：>=0 扣减后余量；-1 余量不足（调用方判 OP-1003）；-2 键缺失（放号生成时预热为
--   total_quota，未预热/已过期即本值，调用方降级信号）。
local remain = tonumber(redis.call('GET', KEYS[1]))
if remain == nil then
    return -2
end
if remain <= 0 then
    return -1
end
remain = redis.call('DECRBY', KEYS[1], tonumber(ARGV[1]))
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return remain
