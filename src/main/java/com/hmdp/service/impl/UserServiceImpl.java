package com.hmdp.service.impl;

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
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.nio.file.CopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     *
     */
    public Result sendcode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        //符合，生成验证码
        String code = RandomUtil.randomString(6);
        session.setAttribute("code", code);

        //保存验证码到redis set key value ex 2s
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code,LOGIN_CODE_TTL, TimeUnit.MINUTES);


        log.debug("发送验证码短信成功：{}",code);
//        log.debug(code);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }
        User user =  query().eq("phone",phone).one();
        //判断用户是否为空
        if(user == null) {
            user = createUserWithPhone(phone);
        }


        //保存用户信息到session中
        //随机生成token,作为登录令牌
        String token = UUID.randomUUID().toString(true);
//        将user对象转为hashmap存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        /**
         * BeanUtil.beanToMap 方法：该方法用于将 JavaBean 转换为 Map。
         * 参数1：源对象，这里是 userDTO 对象。
         * 参数2：目标 Map，这里是 new HashMap<>()，表示创建一个新的 HashMap 来存储转换后的键值对。
         * 参数3：CopyOptions 对象，用于配置转换选项。
         */
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
            CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));

        //存储
        String tokeKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokeKey, userMap);
        //设置token有效期
        stringRedisTemplate.expire(tokeKey,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+ RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
