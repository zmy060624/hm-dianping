package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 分页查询热门笔记（按点赞数排序）
     * @param current 页码
     * @return 笔记列表
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询笔记详情
     * @param id 笔记id
     * @return 笔记详情
     */
    Result queryBlogById(Long id);

    /**
     * 点赞或取消点赞
     * @param id 笔记id
     * @return 无
     */
    Result likeBlog(Long id);

    /**
     * 查询笔记点赞最多的前5个用户
     * @param id 笔记id
     * @return 点赞用户列表
     */
    Result queryBlogLikes(Long id);

    /**
     * 发布探店笔记，并推送到所有粉丝的收件箱
     * @param blog 笔记内容
     * @return 新增笔记的id
     */
    Result savaBlog(Blog blog);

    /**
     * 滚动分页查询当前用户关注的人的笔记
     * @param max 上一次查询的最小时间戳
     * @param offset 时间戳相同的笔记要跳过的条数
     * @return 笔记列表、下次的时间戳和偏移量
     */
    Result queryBlogOfFollow(Long max, Integer offset);

}
