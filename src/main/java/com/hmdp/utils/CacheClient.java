package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * redis工具
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    //常量需要设置初值,构造注入
    public CacheClient(@Autowired StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    /**
     * 将任意对象序列化成json存入redis
     * @param key 键
     * @param value 值
     * @param time 过期时间
     * @param unit 时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 将任意对象序列化成json并且设置逻辑过期时间存入redis
     * @param key 键
     * @param value 值
     * @param time 过期时间
     * @param unit 时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = RedisData
                .builder()
                .data(value)
                .expireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)))
                .build();
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 设置控制解决缓存穿透
     * @param keyPrefix 键前缀
     * @param id 某类型的id
     * @param type 某类型
     * @param dbFallback 某类型的数据库查询函数
     * @param time 过期时间
     * @param unit 时间单位
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix
            , ID id
            , Class<R> type, Function<ID,R> dbFallback
            ,Long time
            ,TimeUnit unit){
        String key= keyPrefix+id;
        //从redis中查
        String json = stringRedisTemplate.opsForValue().get(key);
        //命中返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        if(json!=null||"".equals(json)){//说明是空串,不能用json.equals(""),会爆空指针异常
            return null;
        }
        //没命中查数据库
        R r = dbFallback.apply(id);
        //数据库没查到,返回404
        if(r==null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //数据库查到了,写入redis,返回数据
        this.set(key,r,time,unit);
        return r;
    }

    /**
     * 简易线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 逻辑过期解决缓存击穿
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key=keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)){//不在redis中,不是热点数据
            return null;
        }
        //命中,反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonData = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonData, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期
            return r;
        }
        //已过期,获取互斥锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R newR = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,newR,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //返回过期商铺信息
        return r;
    }
}
