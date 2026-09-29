-- 越限回合标记原子迁移脚本（EX-27/BE-B5-06：原「GET→判定→SET/DEL」三步应用层序列竞态收口）：
-- 判定与写删收在同一脚本原子边界内完成——Redis 单线程串行执行脚本，并发评估同键时仅一方
-- 置标记起算、仅一方消费达标回合，消除「检查与生效」窗口（对齐 fuyun-outpatient
-- PoolRedisGate 的 resources/lua/ + DefaultRedisScript 先例形态）。
-- KEYS[1]=回合标记键 fy:iot:alarm:breach:{ruleId}:{deviceId}:{metricCode}（键语义不变）；
-- ARGV[1]=本批评估基准时刻（epoch 毫秒，兼新回合起算承载值）；ARGV[2]=标记 TTL 秒
--   （持续时长+60s 兜底余量，Java 侧拼装口径不变）；ARGV[3]=规则持续时长秒（可空已按 0 归一）。
-- 返回：0 首越限（已置标记起算，不触发）；1 持续未达标（保留标记静默等待）；
--   2 持续达标（已清标记回合归零，调用方走触发链）。
-- 判定口径与原 Java 逐字等价：elapsed 秒（向零截断，对齐 Duration.getSeconds()）< durationSecs
--   即静默等待；标记承载值编码由 ISO-8601 文本改为 epoch 毫秒数字——Lua 无 ISO 解析能力，数值
--   承载是判定入脚本原子边界的前提；历史残留 ISO 值 tonumber 失败按既有「解析失败按缺席复位」
--   口径重起回合（标记 TTL ≤ 持续时长+60s，残留窗口极短，至多顺延一批起算）。
local marker = redis.call('GET', KEYS[1])
local startMillis = tonumber(marker)
if not marker or not startMillis then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))
    return 0
end
local elapsedMillis = tonumber(ARGV[1]) - startMillis
-- 向零截断取整（负值 ceil / 正值 floor），与 Java Duration.getSeconds() 截断语义逐字对齐
local elapsedSecs = elapsedMillis >= 0 and math.floor(elapsedMillis / 1000) or math.ceil(elapsedMillis / 1000)
if elapsedSecs < tonumber(ARGV[3]) then
    return 1
end
redis.call('DEL', KEYS[1])
return 2
