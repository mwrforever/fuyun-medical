-- 号源池原子回补脚本（退号/取消/超时释放回池，与 pool_deduct.lua 对称；对账防漂移）：
-- KEYS[1]=池键 fy:outpatient:pool:{poolId}（{poolId} 为 Redis Cluster hash tag，A.5-17）；
-- ARGV[1]=回补量（单次退号恒为 1）；ARGV[2]=池总量（越界封顶锚）；ARGV[3]=TTL 秒。
-- 返回：>=0 封顶后余量（INCRBY 后越过池总量即回写封顶，重复回补/对账漂移不放大余量）；
--   -1 键缺失（对账窗口外残留，调用方按需重预热）。
local remain = tonumber(redis.call('GET', KEYS[1]))
if remain == nil then
    return -1
end
remain = redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
local total = tonumber(ARGV[2])
if remain > total then
    -- 越界封顶：余量不得越过池总量（对账差异兜底，与每日 Redis↔池行对账口径一致）
    redis.call('SET', KEYS[1], tostring(total))
    remain = total
end
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return remain
