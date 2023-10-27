package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //线程任务
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true){
                try {
                    //1、获取消息队列中的订单信息
                    //XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.orders >
                    //返回的是list，因为COUNT传递的数值不一定
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            //spring下的Consumer，from（组名，消费者名）
                            Consumer.from("g1", "c1"),
                            //empty()是为了自己去指定
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //StreamOffset.lastConsumed()代表的是>
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2、判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //2.1、获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //解析集合中的订单信息
                    //MapRecord中的String类型存放的是消息的ID， Object, Object类型是存储在消息队列中的键值对形式的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    //Map集合转化为voucherOrder对象，第三个参数代表有错误就忽略
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //3、如果获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    //4、ACK确定
                    //xack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                    //出现异常，信息进入pending-list中
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //1、获取pending-list中的订单信息
                    //XREADGROUP GROUP g1 c1 COUNT 1 STREAM stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2、判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //2.1、说明pending-list中没有异常需要处理，直接break
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //3、如果获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    //4、ACK确定
                    //xack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {//这里处理完异常，继续进入循环，直到没有异常
                    log.error("处理pending-list异常",e);
                    try {//防止抛出异常过于频繁，睡一会
                        Thread.sleep(20);
                        throw new RuntimeException(e);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    //准备线程任务
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true){
//                //1、获取队列中的订单信息
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();//获取和删除该队列的头部，如果需要则等待直到元素可用
//                    //2、创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常",e);
//                }
//            }
//        }
//    }

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
        //订单id(根据redisIdWorker生成全局Id)
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本，并判断结果是否为0。(lua脚本执行了判断用户的购买资格，并且将订单信息发送到消息队列中)
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),//因为lua脚本中key没有参数，所以传入空的集合
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        int r = result.intValue();
        //判断lua脚本返回值，1代表库存不足
        //2代表用户已经下单
        if (r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取用户
//        Long userId = UserHolder.getUser().getId();
//        //执行lua脚本，并判断结果是否为0
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
//                Collections.emptyList(),//因为lua脚本中key没有参数，所以传入空的集合
//                voucherId.toString(),
//                userId.toString()
//        );
//        int r = result.intValue();
//        //判断lua脚本返回值，1代表库存不足
//        //2代表用户已经下单
//        if (r != 0){
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //订单id(根据redisIdWorker生成全局Id)
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        //代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //放入阻塞队列
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //返回订单id
//        return Result.ok(orderId);
//    }

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
