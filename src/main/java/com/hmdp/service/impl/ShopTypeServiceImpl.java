package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;

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
    public Result queryType() {
        String shopTypeCache = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOPLIST_KEY);
        List<ShopType> typeList = null;
        if (StrUtil.isNotBlank(shopTypeCache)){
            List<ShopType> shopType = JSONUtil.toList(shopTypeCache, ShopType.class);
            return Result.ok(shopType);
        }
        typeList = this.list(new LambdaQueryWrapper<ShopType>().orderByAsc(ShopType::getSort));
        if (Objects.isNull(typeList)) {
            // 3.1 数据库中不存在该数据，返回失败信息
            return Result.fail("店铺类型不存在");
        }
        String jsonStr = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOPLIST_KEY, jsonStr, RedisConstants.CACHE_SHOP_TTL, java.util.concurrent.TimeUnit.MINUTES);

        return Result.ok(typeList);
    }
}
