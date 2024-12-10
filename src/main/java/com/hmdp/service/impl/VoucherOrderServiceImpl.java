package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
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
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRPIT;
    static {
        SECKILL_SCRPIT = new DefaultRedisScript<>();
        SECKILL_SCRPIT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRPIT.setResultType(Long.class);

    }
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDERED_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    public void init(){
        SECKILL_ORDERED_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        
        @Override
        public void run(){
            while (true){
                try {
                    //1。获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId =voucherOrder.getUserId();
        //创建锁对象
        RLock lock  = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if (!isLock){
            //获取锁失败，返回错误或重试
            log.error("不允许重复下单");
        }
    try{
        proxy.createVoucherOrder(voucherOrder);
    }finally {
        lock.unlock();
    }
    }

    static Long id = 1L;


    private IVoucherOrderService proxy;
    @Override
    public Result addSeckillVoucher(Long voucherId) {
        //获取用户
        Long userId  = UserHolder.getUser().getId();
        //1,执行lua脚本
        Long result =  stringRedisTemplate.execute(
                SECKILL_SCRPIT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString()
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            //2.1不为0代表没有购买资格
            return Result.fail(r == 1 ? "库存不足":"不能重复下单");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.2为0 有购买资格，把下单信息把保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        // TODO 保存阻塞队列
        voucherOrder.setUserId(orderId);
//        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        //获取代理对象
         proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(0);
    }

//    @Override
//    public Result addSeckillVoucher(Long voucherId) {
//        SeckillVoucher voucher =  seckillVoucherService.getById(voucherId);
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始！");
//        }
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束！");
//        }
//
//        if(voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock  = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock();
//        if (!isLock){
//            //获取锁失败，返回错误或重试
//            return Result.fail("不允许重复下单");
//        }
//        try{
//            //获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();


        //查询订单
        int count  = query().eq("user_id",userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //判断是否存在
        if(count > 0){
            log.error("该用户已经购买过一次");
            return;
        }

        //扣减库存
        boolean success =  seckillVoucherService.update()
                .setSql("stock = stock - 1")//set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0)//where id = ? and stock > 0
                .update();
        if (!success){
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }
}
