package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * ClassName: RedisData
 * Package: com.hmdp.entity
 * Description:
 *
 * @Author:Shooter
 * @Create 2023/10/24 16:06
 * @Version 1.0
 */
@Data
public class RedisData {

    private LocalDateTime expireTime;
    private Object data;

}
