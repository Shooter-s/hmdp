package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    //创建缓存重建所用的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询商户信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存的击穿
//        Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存的击穿
        Shop shop = queryWithLoginExpire(id);

        //这里需要进行判断，当redis命中""之后，返回错误字段。
        if (shop == null){
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    public Shop queryWithLoginExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1、根据redis查询商铺是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、商铺为空
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        //商铺存在，判断缓存是否过期，判断前提需要将shopJson反序列化未java对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonObject, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期，返回旧数据
            return shop;
        }
        //过期尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock){
            //获取锁了，开启新的线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id,30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLocak(lockKey);
                }
            });
        }
        //返回过期的商铺信息
        return shop;
    }

    /**
     * 互斥锁解决缓存的击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
//        1、根据redis查询商品是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2、存在返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断json数据是否为空，若为空，直接拦截
        if (shopJson != null){
            return null;
        }
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取互斥锁
            if (!isLock) {
                //否，让其休眠一会并重试
                Thread.sleep(50);
                //重新从redis中查，做递归
                return queryWithPassThrough(id);
            }
            //注意：获取锁成功再次检测redis缓存是否存在，做DoubleCheck。如果存在则无需重建缓存。
            String shopJsonDouble = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJsonDouble)){
                return JSONUtil.toBean(shopJsonDouble,Shop.class);
            }
            //获取了互斥锁，根据id查询数据库，将商铺数据写入redis
            shop = getById(id);
            //模拟重建的延时，检验锁是否安全。
            Thread.sleep(200);
            if (shop == null){
                //缓存击穿
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //数据库中存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLocak(lockKey);
        }
//        6、返回
        return shop;
    }

    /**
     *处理缓存击穿的代码
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
//        1、根据redis查询商品是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2、存在返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断json数据是否为空，若为空，直接拦截
        if (shopJson != null){
            return null;
        }
//        3、不存在的话根据id查询数据库
        Shop shop = getById(id);
//        4、不存在，将空值写入redis中，报错
        if (shop == null){
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
//        5、存在写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL,TimeUnit.MINUTES);
//        6、返回
        return shop;
    }

    /**
     * 将信息提前加入缓存中
     */
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1、查询店铺数据
        Shop shop = getById(id);
        //模拟高并发
        Thread.sleep(20);
        //2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);
        //3、写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //1、更新数据库
        updateById(shop);
        //2、删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
    private boolean tryLock(String key){
        Boolean isFlag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isFlag);
    }

    private void unLocak(String key){
        stringRedisTemplate.delete(key);
    }

}
