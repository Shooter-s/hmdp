package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

/**
 * ClassName: IFollowService
 * Package: com.hmdp.service
 * Description:
 *
 * @Author:Shooter
 * @Create 2023/10/27 16:24
 * @Version 1.0
 */
public interface IFollowService extends IService<Follow> {
    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);
}
