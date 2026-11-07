package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * token 刷新拦截器
 * <p>
 * 对应流程图第一个方块：拦截<b>一切路径</b>，只做「续期 + 写 ThreadLocal」，<b>不做登录判断</b>。
 * 1、获取 token
 * 2、查询 Redis 里的用户
 * 3、保存到 ThreadLocal
 * 4、刷新 token 有效期
 * 5、放行（不管有没有登录都放行，校验交给下一个 LoginInterceptor）
 * </p>
 * <p>
 * 为什么「不拦截」却要拦所有路径？
 * 因为「只有用户还在访问，登录状态就不能掉」这件事需要对所有请求生效，
 * 包括那些不需要登录的接口（比如首页热门博客）。否则用户逛首页半小时，回来就掉线了。
 * </p>
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 这里用构造注入而不是 @Resource：
     * 拦截器是在 MvcConfig 里 new 出来的，不是 Spring Bean，注解注入不生效，
     * 所以由 MvcConfig 把容器里的 StringRedisTemplate 传进来。
     */
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、获取 token（前端 common.js 放在 authorization 请求头里）
        String token = request.getHeader("authorization");

        // 1.1、没带 token —— 说明是未登录用户，直接放行，交给 LoginInterceptor 去决定要不要拦
        if (StrUtil.isBlank(token)) {
            return true;
        }

        // 2、用 token 拼 key，查询 Redis 里的用户
        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);

        // 2.1、查不到 —— token 已过期或伪造，同样放行（ThreadLocal 里没有用户，后续拦截器会拦）
        if (userMap.isEmpty()) {
            return true;
        }

        // 3、查到了 —— 把 Hash 还原成 UserDTO 存进 ThreadLocal，后续 Controller / Service 靠它拿当前用户
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);

        // 4、刷新 token 有效期（滑动过期：只要还在访问就不掉线）
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 5、放行
        return true;
    }

    /**
     * 请求结束后清理 ThreadLocal
     * <p>
     * Tomcat 线程是复用的，不清会串用户、也会内存泄漏。
     * 两个拦截器都有 afterCompletion，但清理只做一次就够，放这里是因为它拦了所有路径，一定执行过。
     * </p>
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
