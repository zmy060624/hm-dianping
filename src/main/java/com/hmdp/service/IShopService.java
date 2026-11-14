package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询店铺
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 更新数据库
     * @param shop
     */
    Result update(Shop shop);

    /**
     * 根据店铺类型分页查询店铺，带坐标时按距离由近到远返回
     * @param typeId 店铺类型id
     * @param current 页码
     * @param x 经度，可为空
     * @param y 纬度，可为空
     * @return 店铺列表，带距离
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
