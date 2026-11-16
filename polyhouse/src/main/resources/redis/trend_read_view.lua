-- 固定hash内先检查大小再读取，避免损坏的大值直接回传给应用；每次都匹配数据库版本。
if redis.call('TYPE', KEYS[1]).ok ~= 'hash' then return nil end
if redis.call('HSTRLEN', KEYS[1], 'id') ~= 36 or redis.call('HSTRLEN', KEYS[1], 'stamp') ~= 20 then return nil end
local size = redis.call('HSTRLEN', KEYS[1], 'payload')
if size < 1 or size > 1048576 then return nil end
if redis.call('HGET', KEYS[1], 'id') ~= ARGV[1] or redis.call('HGET', KEYS[1], 'stamp') ~= ARGV[2] then return nil end
return redis.call('HGET', KEYS[1], 'payload')
