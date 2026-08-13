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
import org.springframework.context.annotation.Lazy;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
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
    @Resource
    @Lazy
    private IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService executor = new ThreadPoolExecutor(
            10, 20, 10000, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1024 * 1024),
            new ThreadPoolExecutor.DiscardPolicy());

    @PostConstruct
    private void init() {
        // 尝试创建消费者组（stream可能还未创建，先忽略异常）
        createConsumerGroup();
        executor.submit(new VoucherOrderHandler());
    }

    private void createConsumerGroup() {
        try {
            stringRedisTemplate.opsForStream()
                    .createGroup("stream:orders", ReadOffset.from("0"), "g1");
        } catch (Exception e) {
            log.info("消费者组已存在或stream尚未创建: {}", e.getMessage());
        }
    }

    /**
     * redis stream队列实现秒杀
     */

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //获取队列的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream:orders
                    List<MapRecord<String,Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofMillis(2000)),
                            StreamOffset.create("stream:orders", ReadOffset.lastConsumed()));
                    //判断队列是否成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，继续下一个循环
                        continue;
                    }
                    //解析消息订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //获取成功下单
                    handleVoucherOrder(order);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge("stream:orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理线程被中断", e);
                   handlePendingList();
                }
            }
        }
        /**
     * 阻塞队列实现
     */
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    VoucherOrder order = orderTasks.take();
//                    handleVoucherOrder(order);
//                } catch (InterruptedException e) {
//                    log.error("订单处理线程被中断", e);
//                    break;
//                }
//            }
//        }

        private void handleVoucherOrder(VoucherOrder order) {
            Long userId = order.getUserId();
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.error("不允许重复下单");
                return;
            }
            try {
                proxy.createVoucherOrder(order);
            } finally {
                lock.unlock();
            }
        }
        private void handlePendingList() {
            while (true) {
                try {
                    //获取队列的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream:orders 0
                    List<MapRecord<String,Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream:orders", ReadOffset.from("0")));
                    //判断队列是否成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，继续下一个循环
                       break;
                    }
                    //解析消息订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //获取成功下单
                    handleVoucherOrder(order);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge("stream:orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("pending处理失败", e);
                }
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        String userId = UserHolder.getUser().getId().toString();
        // 执行 Lua 脚本
        Long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId,String.valueOf(orderId));
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 返回订单 ID
        return Result.ok(orderId);
    }
    /**
     *
     基于jvm阻塞队列实现异步秒杀
     */
//    String userId = UserHolder.getUser().getId().toString();
//    // 执行 Lua 脚本
//    Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId);
//    int r = result.intValue();
//        if (r != 0) {
//        return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//    }
//    // 获取代理对象（供异步线程调用事务方法）
//    proxy = (IVoucherOrderService) AopContext.currentProxy();
//    // 生成订单并放入阻塞队列
//    Long orderId = redisIdWorker.nextId("order");
//    VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(Long.valueOf(userId));
//        voucherOrder.setVoucherId(voucherId);
//        orderTasks.add(voucherOrder);
//    // 返回订单 ID
//        return Result.ok(orderId);
//}
    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        Long voucherId = order.getVoucherId();
        // 查询用户是否已下单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户 {} 已购买过优惠券 {}", userId, voucherId);
            return;
        }
        // 扣减数据库库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("优惠券 {} 库存不足", voucherId);
            return;
        }
        // 保存订单
        save(order);
    }

}
