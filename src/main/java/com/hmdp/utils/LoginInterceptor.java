package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录校验拦截器
 * <p>
 * 对应流程图第二个方块：只拦<b>需要登录的路径</b>，职责单一 —— 判断 ThreadLocal 里有没有用户。
 * 有：放行
 * 没有：拦截（401）
 * </p>
 * <p>
 * 注意：它自己不查 Redis、不解析 token。
 * 用户到底有没有、是谁，是前一个 RefreshTokenInterceptor 已经处理完放进 ThreadLocal 的。
 * 原来那套查 Redis + 续期 + 写 UserHolder 的逻辑，已经整体搬到 RefreshTokenInterceptor 了。
 * </p>
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、判断 ThreadLocal 里是否有用户（由 RefreshTokenInterceptor 写入）
        if (UserHolder.getUser() == null) {
            // 1.1、没有 —— 未登录，拦截。返回 401，前端 common.js 收到 401 会自动跳转登录页
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        // 2、有 —— 已登录，放行
        return true;
    }
}
