package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    public Result queryTypeList() {
        String key = "cache:shopTypeList";
        List<String> cachedShopTypes = stringRedisTemplate.opsForList().range(key,-1,0);
        if(cachedShopTypes != null && !cachedShopTypes.isEmpty()){
            return Result.ok(cachedShopTypes);
        }
        List<ShopType> shopTypeList = list();
        if (shopTypeList == null || shopTypeList.isEmpty()){
            return Result.fail("店铺类型为空");
        }
        //将数据库中的数据转换为字符串列表并存入redis缓存
        List<String> shopTypeStrings = shopTypeList.stream()
                .map(ShopType::getName)
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().leftPushAll(key,shopTypeStrings);
        stringRedisTemplate.expire(key,1, TimeUnit.MINUTES);
        return Result.ok(shopTypeList);
    }
}
