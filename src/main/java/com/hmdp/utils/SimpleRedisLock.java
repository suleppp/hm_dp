package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;//业务名称
    private StringRedisTemplate stringRedisTemplate;
    private final static String KEY_PRE="lock:";
    private final static String ID_PRE= UUID.randomUUID().toString(true)+"-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void unlock() {
        //判断线程id是否与存入redis中的一致,防止误删其他线程的
        String id = stringRedisTemplate.opsForValue().get(KEY_PRE + name);
        String threadId =ID_PRE+ Thread.currentThread().getId();
        if(threadId.equals(id)){
            stringRedisTemplate.delete(KEY_PRE+name);
        }
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId =ID_PRE+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PRE + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//自动拆箱有空指针的风险,所以这么处理
    }
}
