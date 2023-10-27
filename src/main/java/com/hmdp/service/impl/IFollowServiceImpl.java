package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * ClassName: IFollowServiceImpl
 * Package: com.hmdp.service.impl
 * Description:
 *
 * @Author:Shooter
 * @Create 2023/10/27 16:24
 * @Version 1.0
 */
@Service
public class IFollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //判断是关注还是取关
        Long userId = UserHolder.getUser().getId();
        if (isFollow){
            //关注
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            save(follow);
        }else {
            //取关 delete from tb_follow where user_id = ? and follow_user_id = ?
            remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",followUserId));
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //查询
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }
}
