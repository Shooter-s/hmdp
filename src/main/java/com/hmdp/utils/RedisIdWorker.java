package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1609459200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix){
        //1、生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = second - BEGIN_TIMESTAMP;
        //2、生成序列号
        //注意key不可以要写成动态的，否则有可能超过32位，拼上日期字符串("20220901")
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd");
        String date = now.format(dateTimeFormatter);
        //自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3、拼接并返回，因为返回的是long类型的，所以用位运算去拼接
        //将时间戳左移32位，空出来的32位并上redis自增序列
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        //定义初始时间
        LocalDateTime localDateTime = LocalDateTime.of(2021, 1, 1, 0, 0, 0, 0);
        //获取秒数
        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = " + second);
    }

}
