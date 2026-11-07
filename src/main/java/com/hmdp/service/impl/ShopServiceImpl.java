package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;


    /**
     * 释放锁的Lua脚本：先比对value再删除，保证"判断+删除"的原子性
     * 不能拆成 get + del 两步，否则两步之间锁可能已经过期被别人拿走
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end");
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 查询店铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {

        //方案一：只解决缓存穿透（空值缓存），不防击穿
        //Shop shop = queryWithPassThrough(id);
        //调用工具类的缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //方案二：互斥锁解决缓存击穿（自带穿透防护）
        //Shop shop = queryWithMutex(id);

        //方案三：逻辑过期解决缓存击穿（需要提前预热数据，否则缓存为空时永远返回null）
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,20L,TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在!");
        }

        return Result.ok(shop);
    }

   /**
    * 缓存穿透：空值缓存方案
    * @param id
    * @return
    */
   /*
   public Shop queryWithPassThrough(Long id) {
       String key = CACHE_SHOP_KEY + id;
       //1.从redis查询商铺缓存
       String shopJson = stringRedisTemplate.opsForValue().get(key);
       //2.命中真实数据 反序列化后返回
       if (StrUtil.isNotBlank(shopJson)) {
           return JSONUtil.toBean(shopJson, Shop.class);
       }
       //3.命中的是空值缓存（空字符串），说明数据库里也没有，直接返回null
       //  走到这里已经排除了"有真实数据"的情况，剩下的非null只可能是空值缓存
       if (shopJson != null) {
           return null;
       }
       //4.未命中 根据id查询数据库
       Shop shop = getById(id);
       if (shop == null) {
           //5.数据库中不存在 写入空值缓存，TTL要短，避免长时间和真实数据不一致
           stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
           return null;
       }
       //6.存在 写入redis，TTL加随机值防止雪崩
       long ttl = CACHE_SHOP_TTL + RandomUtil.randomLong(0, 10);
       stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), ttl, TimeUnit.MINUTES);
       return shop;
   }
    */

    /**
     * 缓存击穿：互斥锁方案
     * 同时具备缓存穿透防护（空值缓存）
     * @param id
     * @return
     */

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.命中真实数据 直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.命中的是空值缓存 返回null
        if (shopJson != null) {
            return null;
        }

        //4.未命中 尝试获取互斥锁重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        String token = null;
        try {
            //4.1 抢锁，最多等5轮，每轮50ms，总计不超过250ms
            for (int i = 0; i < 5; i++) {
                token = tryLock(lockKey);
                if (token != null) {
                    break;
                }
                Thread.sleep(50);
            }
            //4.2 抢锁超时：降级直接查库，宁可多打一次数据库，也不能让请求一直等着
            if (token == null) {
                return getById(id);
            }
            //4.3 双重检查：抢锁这段时间里可能已经有别的线程把缓存重建好了，再查一次避免重复查库
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            //4.4 拿到锁 根据id查询数据库
            Shop shop = getById(id);
            //5.数据库不存在 写入空值缓存后返回
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在 写入redis，TTL加随机值防止雪崩
            long ttl = CACHE_SHOP_TTL + RandomUtil.randomLong(0, 10);
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), ttl, TimeUnit.MINUTES);
            return shop;
        } catch (InterruptedException e) {
            //被打断要恢复中断标记，否则上层感知不到
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁，只释放自己加的锁
            if (token != null) {
                unLock(lockKey, token);
            }
        }
    }

    /**
     * 缓存击穿：逻辑过期方案
     * 缓存不设物理TTL，靠RedisData里的expireTime判断是否过期
     * 过期后异步重建，期间所有请求都返回旧数据，不阻塞
     * @param id
     * @return
     *//**
     * 缓存重建用的线程池，固定10个线程
     */
     private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

     public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
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
        Shop shop = redisData.getData() == null
                ? null
                : JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否过期（expireTime为空属于脏数据，当作已过期处理）
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            //4.1 未过期 直接返回
            return shop;
        }

        //4.2 已过期 需要缓存重建
        //5.尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        String token = tryLock(lockKey);
        //6.抢到锁 开启独立线程异步重建，不阻塞当前请求
        if (token != null) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    //线程池里的异常不会被主线程感知，必须自己打日志，否则重建失败没人知道
                    log.error("缓存重建失败, shopId=" + id, e);
                } finally {
                    unLock(lockKey, token);
                }
            });
        }
        //7.没抢到锁 或 已提交重建任务，都先把过期数据返回给前端
        return shop;
    }

    /**
     * 逻辑过期：把店铺数据带着过期时间写入redis
     * @param id
     * @param expireSeconds
     */
    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        //店铺不存在就没必要写缓存，否则会写出一条data为null的脏数据
        if (shop == null) {
            log.warn("缓存预热跳过，店铺不存在, shopId=" + id);
            return;
        }
        //模拟复杂查询耗时，用来观察"重建期间其他请求返回旧数据"的效果，实际项目删掉
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis，逻辑过期方案不设物理TTL
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 修改数据库
     *
     * @param shop
     */

    @Override
    @Transactional
    public Result update(Shop shop) {
        //1.校验并更新数据库
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        //2.事务提交后再删缓存
        //  如果现在就删，事务还没提交，其他请求来查库读到的还是旧值，
        //  会把旧值重新回填进缓存，缓存就一直是脏的，直到TTL过期
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
            }
        });

        return Result.ok();
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
