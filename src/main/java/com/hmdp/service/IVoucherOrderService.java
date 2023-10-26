package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/**
 * ClassName: IVoucherOrderService
 * Package: com.hmdp.service
 * Description:
 *
 * @Author:Shooter
 * @Create 2023/10/25 12:55
 * @Version 1.0
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * 下单秒杀卷
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);
}
