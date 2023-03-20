package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringredisTemplate;
    private String name;
    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX=UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLock(StringRedisTemplate stringredisTemplate, String name) {
        this.stringredisTemplate = stringredisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String value=ID_PREFIX+Thread.currentThread().getName();

        Boolean success = stringredisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, value, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        String threadID=ID_PREFIX+Thread.currentThread().getName();
//        String id = stringredisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if(threadID.equals(id)){
//            stringredisTemplate.delete(KEY_PREFIX+name);
//        }
        //调用lua脚本
        stringredisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                threadID);

    }
}
