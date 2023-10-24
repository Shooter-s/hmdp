package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
        Shop shop = queryWithMutex(id);
        //这里需要进行判断，当redis命中""之后，返回错误字段。
        if (shop == null){
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
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
