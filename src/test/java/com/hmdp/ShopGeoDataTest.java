package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * 一次性任务：把数据库里店铺的坐标导入 Redis 的 GEO 集合
 * <p>
 * 附近商铺功能依赖 shop:geo:{typeId} 这些 key，
 * 这个 key 不会被业务代码自动写入，必须手动跑一次。
 * </p>
 */
@SpringBootTest
class ShopGeoDataTest {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loadShopGeoData() {
        //1.查出所有店铺
        List<Shop> shops = shopService.list();
        //2.按 typeId 分组，没有坐标的店铺跳过（否则构造 Point 会 NPE）
        Map<Long, List<Shop>> map = shops.stream()
                .filter(shop -> shop.getX() != null && shop.getY() != null)
                .collect(Collectors.groupingBy(Shop::getTypeId));
        //3.一个分类一个 key，批量写进 GEO
        int total = 0;
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            String key = SHOP_GEO_KEY + entry.getKey();
            List<RedisGeoCommands.GeoLocation<String>> locations =
                    new ArrayList<>(entry.getValue().size());
            for (Shop shop : entry.getValue()) {
                //Point(x, y)：x 是经度，y 是纬度，顺序不能反
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
            total += locations.size();
            System.out.println(key + " 写入 " + locations.size() + " 条");
        }
        System.out.println("Geo 数据导入完成，共 " + total + " 条");
    }
}
