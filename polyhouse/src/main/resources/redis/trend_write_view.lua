-- 时间戳为20位UTC微秒字符串，使用字典序比较，不能转换为会丢失微秒精度的Lua double。
if string.len(ARGV[1]) ~= 36 or string.len(ARGV[2]) ~= 20 or not string.match(ARGV[2], '^%d+$')
   or string.len(ARGV[3]) < 1 or string.len(ARGV[3]) > 1048576 then return 0 end
local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'hash' then return 0 end
if kind == 'hash' then
    -- 损坏的id/stamp也可能过大，必须先查长度，不能先HGET再在Lua中复制巨值。
    if redis.call('HSTRLEN', KEYS[1], 'stamp') == 20 and redis.call('HSTRLEN', KEYS[1], 'id') == 36 then
        local stamp = redis.call('HGET', KEYS[1], 'stamp')
        local id = redis.call('HGET', KEYS[1], 'id')
        if string.match(stamp, '^%d+$') then
        if stamp > ARGV[2] or (stamp == ARGV[2] and id ~= ARGV[1]) then return 0 end
        end
    end
end
-- 每次成功写重建恰好三字段并设置固定TTL，不积累日期/用户键，也不受未知字段污染。
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'id', ARGV[1], 'stamp', ARGV[2], 'payload', ARGV[3])
redis.call('EXPIRE', KEYS[1], 900)
return 1
