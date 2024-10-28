package com.hmdp.service.impl;

import cn.hutool.Hutool;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone= loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误!");
        }
        String cacheCode =stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+phone);
        String code=loginForm.getCode();
        if(cacheCode==null||!cacheCode.equals(code)){
            return Result.fail("验证码过期或错误");
        }
        User user = query().eq("phone", phone).one();
        if(user==null){
            user=createUserWithPhone(phone);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将dto转换为map,并将id为long类型转换为string
        Map<String, Object> map = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldVal)->fieldVal.toString()));
        //生成token作为redis的key
        String token =UUID.randomUUID().toString(true);
        String tokenKey=RedisConstants.LOGIN_USER_KEY+token;
        //存储
        stringRedisTemplate.opsForHash().putAll(tokenKey,map);
        //设置有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.DAYS);
        return Result.ok(token);
    }

    /**
     * 根据手机号创建用户对象
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user=User.builder()
                .phone(phone)
                .nickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10))
                .build();
        save(user);
        return user;
    }

    /**
     * 发送短信验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合,返回错误信息
            return Result.fail("手机号格式错误");
        }
        //符合,生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送短信验证码成功,验证码:{}",code);
        //返回ok
        return Result.ok();
    }
}
