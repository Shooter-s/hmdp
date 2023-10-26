package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * ClassName: IVoucherOrderServiceImpl
 * Package: com.hmdp.service.impl
 * Description:
 *
 * @Author:Shooter
 * @Create 2023/10/25 12:56
 * @Version 1.0
 */
@Service
@Slf4j
public class IVoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;//快捷键shift+F6将所有同名的一键更改
    //初始化脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //阻塞队列orderTasks，当一个线程尝试获取该队列中元素，该队列中没有元素，则会被阻塞，直到有元素
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //准备线程任务
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                //1、获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();//获取和删除该队列的头部，如果需要则等待直到元素可用
                    //2、创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户，因为该线程不是主线程，无法从ThreadLocal中去取值
        Long userId = voucherOrder.getUserId();
        //创建锁对象(这里讲道理可以不用做锁，完全是兜底)
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if (!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            //将voucherOrder保存到数据库中
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本，并判断结果是否为0
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),//因为lua脚本中key没有参数，所以传入空的集合
                voucherId.toString(),
                userId.toString()
        );
        int r = result.intValue();
        //判断lua脚本返回值，1代表库存不足
        //2代表用户已经下单
        if (r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id(根据redisIdWorker生成全局Id)
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1、查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始!");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束!");
//        }
//        //2、判断库存是否充足，充足的话修改库存
//        if (seckillVoucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//        //分布式锁
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象(针对单个用户刷单情况，所以把userId拼接进来)
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock();//第一个参数是默认等待时间，第二个和第三个参数是有效时常
//        if (!isLock){
//            return Result.fail("你点击过快");
//        }
//        try {
//            //拿到事务代理的对象，目标对象是实现类实现的接口
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            //用代理对象调用事务管理的方法。基于接口去调用
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        //用户id
        Long userId = voucherOrder.getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //订单已存在
        if (count > 0){
            log.error("用户已经购买过一次了!");
            return;
        }
        //修改数据库
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                //乐观锁(防止超卖)
                .gt("stock",0).update();
        if (!success){
            log.error("库存不足!");
            return;
        }
        //保存到数据库中
        save(voucherOrder);
    }
}
