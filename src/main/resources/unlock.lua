--锁的key
local key=KEYS[1]
--当前 线程标识
local threadId=ARGV[1]

--锁的线程标识
local id= redis.call('get',key)
if(id==threadId) then
    return redis.call('del',key)

end
return 0