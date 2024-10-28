package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    /**
     * 根据商铺id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        //Shop shop=queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if(shop==null) return Result.fail("商铺不存在");
        return Result.ok(shop);
    }

    //需要在测试类中使用到这个函数插入预热数据
    public void saveShop2Redis(Long id,Long expireSecond){
        Shop shop = getById(id);
        RedisData redisData = RedisData.builder().
                expireTime(LocalDateTime.now().plusSeconds(expireSecond)).
                data(shop).
                build();
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    //逻辑过期解决缓存击穿(前提保证redis中存在预热数据)
    private Shop queryWithLogicalExpire(Long id){
        String key=RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){//不在redis中,不是活动商铺
            return null;
        }

        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject jsonData = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonData, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期
            return shop;
        }

        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }

    //互斥锁解决缓存穿透
    /**
     * 理清逻辑:这个函数既解决了缓存穿透(攻击)又解决了热点击穿的问题
     * 先从redis中查,查到了,返回shop说明真的有,返回null说明数据库没有,空值写入reids防止穿透攻击
     * 如果redis没有,就要拿到锁,防止同一大量的key来访问数据库,只要一个线程访问即可
     * 没有拿到的线程睡眠等待,从数据库拿到的,不管是null还是shop都写入redis
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id){
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        //从redis中查
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //命中返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson!=null){//说明是空串,不能用equals,会爆空指针异常
            return null;
        }
        //没命中查数据库,这里要解决缓存击穿问题
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean flag = tryLock(lockKey);
            if(flag==false){//没有拿到锁
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            shop = getById(id);
            //Thread.sleep(200);
            //数据库没查到,返回404
            if(shop==null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //数据库查到了,写入redis,返回数据
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(lockKey);
        }

        return shop;
    }

    //设置空值来防止缓存穿透
    private Shop queryWithPassThrough(Long id){
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        //从redis中查
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //命中返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson!=null){//说明是空串,不能用equals,会爆空指针异常
            return null;
        }
        //没命中查数据库
        Shop shop = getById(id);
        //数据库没查到,返回404
        if(shop==null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //数据库查到了,写入redis,返回数据
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key){
        //如果这个锁不存在就设置这个锁,存在就设置不了
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MILLISECONDS);
        return BooleanUtil.isTrue(flag);//防止包装类返回null
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺ID不能为NULL");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
