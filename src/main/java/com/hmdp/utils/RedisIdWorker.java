package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * redis实现全局唯一id
 */
@Component
public class RedisIdWorker {
    /**
     * 初始时间戳
     */
    private static final Long BEGIN_TIMESTAMP=1730194431L;
    /**
     * 序列化位数
     */
    private static final Integer COUNT_BITS=32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取id
     * 不直接使用Redis自增的数值，而是拼接一些其它信息：
     * ID的组成部分：符号位：1bit，永远为0
     * 时间戳：31bit，以秒为单位，可以使用69年
     * 序列号：32bit，秒内的计数器，支持每秒产生2^32个不同ID
     * @param keyPrefix 业务前缀
     * @return
     */
    public Long nextId(String keyPrefix){
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowSecond-BEGIN_TIMESTAMP;
        //获取当前日期,精确到天
        String today=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + today);
        return timeStamp<<COUNT_BITS|count;
    }
}
