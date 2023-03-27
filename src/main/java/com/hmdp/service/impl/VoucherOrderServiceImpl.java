package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.val;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private RedisIdWorker redisIdWorker;
    private StringRedisTemplate stringRedisTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //阻塞队列实现队列
//    private BlockingQueue<VoucherOrder> blockingQueue=new ArrayBlockingQueue<>(1024*1024);
//    private class VoucherOrderHandle implements Runnable{
//
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    VoucherOrder voucherOrder = blockingQueue.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//
//            }
//        }
//    }



    private  static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    private RedissonClient redissonClient;
    IVoucherOrderService proxy;
    @PostConstruct
    private  void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
    }
    private class VoucherOrderHandle implements Runnable{
        String queueName="stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if(read==null||read.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> record = read.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(order);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    e.printStackTrace();
                    handlePendingList();
                }

            }
        }
        private  void handlePendingList(){
            while(true){
                try {
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if(read==null||read.isEmpty()){
                        break;
                    }
                    MapRecord<String, Object, Object> record = read.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(order);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId= voucherOrder.getUserId();
        Long voucherId=voucherOrder.getId();
        RLock lock=redissonClient.getLock("lock:order:"+userId);
        boolean islock=lock.tryLock();

        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock",0)
                .update();
        save(voucherOrder);
    }

    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId=UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //异步秒杀
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                String.valueOf(orderId)
        );
        //2. 判断返回值，并返回错误信息
        if (result.intValue() != 0) {
            return Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
        }

        //TODO 保存阻塞队列
        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //blockingQueue.add(voucherOrder);
        proxy= (IVoucherOrderService) AopContext.currentProxy();
        //3. 返回订单id
        return Result.ok(orderId);


//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("已结束");
//        }
//        if (voucher.getStock()<1) {
//            return  Result.fail("库存不足");
//        }
//        Long userId= UserHolder.getUser().getId();
//        synchronized(userId.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
        //创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        if (!lock.tryLock(5)) {
//             return Result.fail("重复下单");
//        }
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }

    }
    @Override
    @Transactional
    public  Result createVoucherOrder(Long voucherId) {

        Long userId= UserHolder.getUser().getId();

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count>0){
            return Result.fail("已购买");
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock",0)
                .update();
        if(!success){
            return  Result.fail("库存不足");
        }

        VoucherOrder voucherOrder=new VoucherOrder();
        long id=redisIdWorker.nextId("order");
        voucherOrder.setId(id);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(id);
        save(voucherOrder);
        return Result.ok(id);



    }
}
