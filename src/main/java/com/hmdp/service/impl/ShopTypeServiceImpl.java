package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_LIST;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> getTypeList() {
        //1.从redis查询分类缓存（固定key，存的是整个列表的JSON数组）
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_LIST);
        //2.命中 反序列化后直接返回（空列表会存成"[]"，同样命中，属于预期）
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toList(json, ShopType.class);
        }
        //3.未命中 查询数据库 按sort升序排列
        List<ShopType> list = query().orderByAsc("sort").list();

        //4.写回缓存 TTL加随机值
        // 说明：分类列表只有这一个key，本身不存在"大量key同时过期"的雪崩，
        // 这里加随机值只是和店铺缓存保持一致的写法，TTL抖动几十秒没有副作用
        long ttl = CACHE_SHOP_TYPE_TTL + RandomUtil.randomLong(0, 10);
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_TYPE_LIST,
                JSONUtil.toJsonStr(list),
                ttl,
                TimeUnit.MINUTES);

        return list;
    }

}
