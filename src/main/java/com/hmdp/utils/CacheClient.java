package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end");
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    /**
     * 将任意对象转成json形式的字符串
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key , Object value , Long time , TimeUnit unit){
        String jsonStr = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key,jsonStr,time,unit);
    }

    public void setWithLogicalExpire(String key , Object value , Long time , TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        String jsonStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key,jsonStr);
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix , ID id, Class<R> type, Function<ID,R> dbFallback, Long time , TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.命中真实数据 反序列化后返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //3.命中的是空值缓存（空字符串），说明数据库里也没有，直接返回null
        //  走到这里已经排除了"有真实数据"的情况，剩下的非null只可能是空值缓存
        if (json != null) {
            return null;
        }
        //4.未命中 根据id查询数据库
        R r = dbFallback.apply(id);

        if (r == null) {
            //5.数据库中不存在 写入空值缓存，TTL要短，避免长时间和真实数据不一致
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在 写入redis，TTL加随机值防止雪崩
        this.set(key,r,time,unit);

        return r;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R  queryWithLogicalExpire(String keyPrefix , ID id , Class<R> type ,Function<ID,R> dbFallback, Long time , TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.未命中 直接返回null
        //  逻辑过期方案要求数据提前预热，缓存里本来就该有数据；
        //  如果没有，说明预热没做或者key不对，这里不查库兜底
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        //3.命中 把json反序列化为RedisData，再从中取出Shop
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //data为null说明缓存里是条脏数据，不能直接强转反序列化，否则NPE
        R r = redisData.getData() == null ? null
                : JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否过期（expireTime为空属于脏数据，当作已过期处理）
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            //4.1 未过期 直接返回
            return r;
        }

        //4.2 已过期 需要缓存重建
        //5.尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        String token = tryLock(lockKey);
        //6.抢到锁 开启独立线程异步重建，不阻塞当前请求
        if (token != null) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查数据库 重建缓存
                    R r1 = dbFallback.apply(id);
                    //查到了就按逻辑过期格式写回redis，否则缓存里的expireTime永远不变，会一直判定过期
                    if (r1 != null) {
                        setWithLogicalExpire(key, r, time, unit);
                    }
                } catch (Exception e) {
                    //线程池里的异常不会被主线程感知，必须自己打日志，否则重建失败没人知道
                    log.error("缓存重建失败, shopId=" + id, e);
                } finally {
                    unLock(lockKey, token);
                }
            });
        }
        //7.没抢到锁 或 已提交重建任务，都先把过期数据返回给前端
        return r;
    }


    /**
     * 获得互斥锁
     * @param key
     * @return 抢锁成功返回本次的token，失败返回null
     */
    private String tryLock(String key) {
        //value存一个唯一标识，释放锁时用它来确认"这把锁是不是我加的"
        String token = UUID.randomUUID().toString(true);
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, LOCK_SHOP_TTL, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag) ? token : null;
    }

    /**
     * 释放互斥锁
     * @param key
     * @param token 加锁时返回的token
     */
    private void unLock(String key, String token) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }


}
