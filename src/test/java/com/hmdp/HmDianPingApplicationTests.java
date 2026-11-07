package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;

    /**
     * 逻辑过期方案预热：把数据库里所有店铺写进 Redis
     * 逻辑过期方案要求缓存里事先有数据，否则查询时缓存未命中会直接返回null
     */
    @Test
    void warmUpShopCache() {
        List<Shop> shops = shopService.list();
        for (Shop shop : shops) {
            cacheClient.setWithLogicalExpire(
                    CACHE_SHOP_KEY + shop.getId(), shop, 20L, TimeUnit.SECONDS);
        }
        System.out.println("预热完成，共写入 " + shops.size() + " 条");
    }

}
