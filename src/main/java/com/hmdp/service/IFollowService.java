package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注或取关
     * @param followUserId 被关注或取关的用户id
     * @param isFollow true关注，false取关
     * @return 无
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 查询当前用户是否已经关注该用户
     * @param followUserId 目标用户id
     * @return true已关注
     */
    Result isFollow(Long followUserId);

    /**
     * 查询当前用户和目标用户的共同关注
     * @param id 目标用户id
     * @return 共同关注的用户列表
     */
    Result followCommons(Long id);
}
