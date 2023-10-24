package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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

    /**
     * 店铺类型查询业务
     * @return
     */
    @Override
    public Result queryOrderBySort() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        //1、从redis中查
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        //2、redis中有
        if (StrUtil.isNotBlank(shopTypeJson)){
            List<ShopType> list = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(list);
        }
        //3、从数据库中查询
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //4、如果数据库中没有
        if (shopTypes == null || shopTypes.size() == 0){
            return Result.fail("没有店铺类型");
        }
        //5、将数据库中的数据加入redis中
        String jsonStr = JSONUtil.toJsonStr(shopTypes);
        stringRedisTemplate.opsForValue().set(key,jsonStr);
        return Result.ok(shopTypes);
    }
}
