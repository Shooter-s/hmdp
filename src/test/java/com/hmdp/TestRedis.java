package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

/**
 * ClassName: TestRedis
 * Package: com.hmdp
 * Description:
 *
 * @Author:Shooter
 * @Create 2023/10/26 22:15
 * @Version 1.0
 */
@SpringBootTest
public class TestRedis {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Test
    public void test(){
        stringRedisTemplate.opsForValue().set("name","张三");
    }

}
