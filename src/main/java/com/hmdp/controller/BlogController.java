package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;


    /**
     * 发布探店笔记
     * @param blog 笔记内容
     * @return 新增笔记的id
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        if(blog.getShopId() == null ){
            return Result.fail("必须关联商户");
        }
        return blogService.savaBlog(blog);
    }

    /**
     * 点赞或取消点赞
     * @param id 笔记id
     * @return 无
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {

        return blogService.likeBlog(id);
    }

    /**
     * 分页查询当前登录用户自己发布的笔记
     * @param current 页码
     * @return 笔记列表
     */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 分页查询热门笔记（按点赞数排序）
     * @param current 页码
     * @return 笔记列表
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 根据id查询笔记详情（含作者信息和当前用户是否点赞）
     * @param id 笔记id
     * @return 笔记详情
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id){
         return blogService.queryBlogById(id);
    }

    /**
     * 查询笔记点赞最多的前5个用户
     * @param id 笔记id
     * @return 点赞用户列表
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id){
        return blogService.queryBlogLikes(id);
    }

    // BlogController
    /**
     * 根据用户id分页查询该用户发布的笔记
     * @param current 页码
     * @param id 用户id
     * @return 笔记列表
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }


    /**
     * 滚动分页查询当前用户关注的人的笔记（收件箱）
     * @param max 上一次查询的最小时间戳
     * @param offset 时间戳相同的笔记要跳过的条数
     * @return 笔记列表、下次的时间戳和偏移量
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max ,
            @RequestParam(value = "offset",defaultValue = "0") Integer offset ) {
        return blogService.queryBlogOfFollow(max , offset);
    }

}
