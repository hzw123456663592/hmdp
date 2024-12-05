package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static cn.hutool.http.ContentType.JSON;
import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        //互斥锁解决缓存穿透
        Shop shop = queryWithmutex(id);
        if (shop == null)
            return Result.fail("店铺不存在");
       return Result.ok(shop);
    }
    public Shop queryWithmutex(Long id) {
        String key =  CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null){
            return null;
        }
        //实现缓存重建
        //获取互斥锁
        String lockKey = "lock:shop" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取成功
            if(!isLock){
                //失败则休眠并重试
                Thread.sleep(50);
                 return queryWithmutex(id);
            }

            shop = getById(id);
            //模拟重建的延时
            Thread.sleep(200);
            if(shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

            //释放互斥锁
            unlock(lockKey);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return shop;
    }


    public Shop queryWithPassThrough(Long id) {
        String key =  CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null){
            return null;
        }
        Shop shop = getById(id);
        if(shop == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 尝试获取分布式锁
     *
     * @param key 锁的键值，用于唯一标识这把锁
     * @return 返回是否成功获取锁
     */
    private boolean tryLock(String key){
        // 使用Redis的setIfAbsent功能来尝试设置键值，如果键不存在，则设置成功，表示获取锁成功
        // 参数分别为：键，值，过期时间，时间单位
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 使用BooleanUtil.isTrue判断设置操作的返回值是否为true，表示是否成功获取锁
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        //1，先更新数据库内容
        updateById(shop);
        if (id == null){
            return Result.fail("id为空");
        }
        //更新redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
