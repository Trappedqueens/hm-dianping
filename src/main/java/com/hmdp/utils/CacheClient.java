package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
@RequiredArgsConstructor
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;


    public  void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    public  void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData data = new RedisData();
        data.setData( value);
        data.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds( time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data));
    }
    public <R,ID> R queryWithPassThrough(String keyPrefix,ID id,Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key=keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank( json)){
            return JSONUtil.toBean(json,type);
        }
        if ("".equals(json)&&json !=null){
            return null;
        }
        R r=dbFallback.apply(id);
        if (r==null){
            stringRedisTemplate.opsForValue().set(key,"",  RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key,r,time,unit);
        return r;
    }
    private  static final ExecutorService executor = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) throws InterruptedException {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //命中
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //检查是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期返回店铺
            return r;
        }
        //过期需要重建
        //缓存重建
        String lockKey = keyPrefix + id;
        boolean isLock = tryLock(lockKey);
        if (isLock){
            ID finalId = id;
            executor.submit(()->{
                try {
                    R r1 = dbFallback.apply(finalId);
                    this.setWithLogicExpire(key,r1,time,unit);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
