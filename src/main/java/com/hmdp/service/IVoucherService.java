package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    /**
     * 查询店铺的所有优惠券
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    Result queryVoucherOfShop(Long shopId);

    /**
     * 新增秒杀券，同时写入秒杀库存
     * @param voucher 优惠券信息，包含秒杀信息
     */
    void addSeckillVoucher(Voucher voucher);
}
