package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getTypeList() {
        String typeKey= RedisConstants.CACHE_TYPE_KEY;
        //通过redis查list
        Long typeListSize = stringRedisTemplate.opsForList().size(typeKey);
        if (typeListSize!=null&&typeListSize!=0){
            //查到了,将json数据转换成pojo
            ArrayList<ShopType> typeList = new ArrayList<>();
            List<String> jsonTypeList = stringRedisTemplate.opsForList().range(typeKey, 0, typeListSize - 1);
            for(String json:jsonTypeList){
                typeList.add(JSONUtil.toBean(json,ShopType.class));
            }
            return Result.ok(typeList);
        }
        //没在redis中查到,查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //数据库没查到
        if (typeList==null) return Result.ok("未知错误");
        List<String> jsonTypeList=new ArrayList<>();
        for(ShopType shopType:typeList){
            jsonTypeList.add(JSONUtil.toJsonStr(shopType));
        }
        //将数据写入redis
        stringRedisTemplate.opsForList().rightPushAll(typeKey,jsonTypeList);
        stringRedisTemplate.expire(typeKey,RedisConstants.CACHE_SHOP_TYPE_TTL,TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
