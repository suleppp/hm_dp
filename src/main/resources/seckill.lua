---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by 31641.
--- DateTime: 2024/11/2 星期六 13:57
---

-- 此lua脚本用于判断秒杀库存,一人一单,决定用户是否抢购成功

-- 1.参数列表
-- 1.1.优惠券id
local voucherId=ARGV[1]
-- 1.2.用户id
local userId=ARGV[2]
-- 1.3.订单id
local orderId=ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey='seckill:stock:'..voucherId -- 优惠券库存
-- 2.2.订单key
local orderKey='seckill:order:'..voucherId -- 该优惠券已经被下的订单

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
if(tonumber(redis.call('get',stockKey))<=0) then
    -- 库存不足
    return 1
end

-- 3.2.判断用户是否下单 sismember orderKey userId
if(redis.call('sismember',orderKey,userId)==1) then
    -- 用户已经购买过
    return 2
end

-- 3.3.扣库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)
-- 3.4.下单保存用户 sadd orderKey userId
redis.call('sadd',orderKey,userId)
return 0
