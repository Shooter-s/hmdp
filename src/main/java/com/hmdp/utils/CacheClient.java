package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 处理缓存击穿的代码
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit
    ) {
        String key = keyPrefix + id;
        //1、根据redis查询商品是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、存在返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //判断json数据是否为空，若为空，直接拦截
        if (json != null) {
            return null;
        }
        //3、不存在的话根据id查询数据库，这里的逻辑交给调用者去写，用函数式编程
        R r = dbFallback.apply(id);
        //4、不存在，将空值写入redis中，报错
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //5、存在写入redis
        this.set(key, r, time, unit);
        //6、返回
        return r;
    }

    //逻辑过期解决缓存的击穿
    public <R, ID> R queryWithLoginExpire(
            String keyPrefix, ID id, Class<R> type, Long time, TimeUnit unit,
            Function<ID, R> dbFallback
    ) {
        String key = keyPrefix + id;
        //1、根据redis查询商铺是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、商铺为空
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //商铺存在，判断缓存是否过期，判断前提需要将shopJson反序列化未java对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，返回旧数据
            return r;
        }
        //过期尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //获取锁了，开启新的线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查数据库
                    R r1 = dbFallback.apply(id);
                    //重建缓存
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLocak(lockKey);
                }
            });
        }
        //返回过期的商铺信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean isFlag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isFlag);
    }

    private void unLocak(String key) {
        stringRedisTemplate.delete(key);
    }

}
