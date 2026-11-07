package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * SpringMVC 配置类：注册两个拦截器
 * <p>
 * 执行顺序由 order 决定，小的先执行：
 * 0. RefreshTokenInterceptor —— 拦一切路径，只刷新 token 有效期 + 写 ThreadLocal，永远放行
 * 1. LoginInterceptor       —— 只拦需要登录的路径，判断 ThreadLocal 有没有用户
 * </p>
 * <p>
 * 为什么顺序不能反：LoginInterceptor 判断的依据是 ThreadLocal，
 * 而 ThreadLocal 是 RefreshTokenInterceptor 填的。反了的话永远判为未登录。
 * </p>
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 0、token 刷新拦截器：拦截一切路径，不拦人，只续期
        //    拦截器是 new 出来的、不是 Bean，所以 RedisTemplate 通过构造函数传进去
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);

        // 1、登录校验拦截器：拦一切路径，但放行不需要登录就能访问的接口
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/code",       // 发送验证码
                        "/user/login",      // 登录
                        "/blog/hot",        // 首页热门博客
                        "/shop/**",         // 店铺查询、探店笔记分页（未登录也能看）
                        "/shop-type/**",    // 店铺分类
                        "/upload/**",       // 图片上传/静态资源
                        "/voucher/**"       // 优惠券查询
                )
                .order(1);
    }
}
