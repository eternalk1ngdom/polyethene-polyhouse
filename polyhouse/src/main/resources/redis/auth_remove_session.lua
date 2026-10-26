-- 旧令牌只能移除自己的会话，不能在用户重新登录后删除新的 jti。
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
