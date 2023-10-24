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
import java.util.function.Function;

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
    @Resource
    private CacheClient cacheClient;

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
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, new Function<Long, Shop>() {
//            @Override
//            public Shop apply(Long aLong) {
//                return getById(aLong);
//            }
//        }, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //缓存击穿
        Shop shop = cacheClient.queryWithLoginExpire(CACHE_SHOP_KEY, id, Shop.class,
                CACHE_SHOP_TTL, TimeUnit.MINUTES,
                this::getById//id2 -> getById(id2)
        );

        //这里需要进行判断，当redis命中""之后，返回错误字段。
        if (shop == null){
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
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

}
