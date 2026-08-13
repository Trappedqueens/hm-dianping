package com.hmdp;

import org.junit.jupiter.api.Test;
import org.redisson.RedissonMultiLock;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class RedissonLockTest {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 测试1：可重入锁
     * 同一线程可以多次获取同一把锁，不会死锁
     */
    @Test
    void testReentrantLock() {
        RLock lock = redissonClient.getLock("test:reentrant");

        System.out.println("=== 可重入锁测试 ===");

        lock.lock();  // 第1次加锁
        System.out.println("第1次获取锁，锁计数: 1");

        lock.lock();  // 第2次加锁（同一线程，不阻塞）
        System.out.println("第2次获取锁，锁计数: 2");

        lock.lock();  // 第3次加锁
        System.out.println("第3次获取锁，锁计数: 3");

        // 模拟业务
        System.out.println("执行业务逻辑...");

        lock.unlock();  // 释放1次，计数=2
        System.out.println("释放1次，锁计数: 2");

        lock.unlock();  // 释放1次，计数=1
        System.out.println("释放1次，锁计数: 1");

        lock.unlock();  // 释放1次，计数=0，锁真正释放
        System.out.println("释放1次，锁计数: 0，锁已释放");
    }

    /**
     * 测试2：tryLock - 不等待，立即返回
     */
    @Test
    void testTryLockNoWait() throws InterruptedException {
        RLock lock = redissonClient.getLock("test:nowait");

        System.out.println("=== tryLock 不等待测试 ===");

        // 线程1获取锁
        new Thread(() -> {
            lock.lock();
            System.out.println("线程1: 获取锁成功");
            try {
                Thread.sleep(5000);  // 持有锁5秒
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
                System.out.println("线程1: 释放锁");
            }
        }).start();

        Thread.sleep(100);  // 确保线程1先获取锁

        // 线程2尝试获取锁（不等待）
        boolean locked = lock.tryLock();
        System.out.println("线程2: tryLock结果 = " + locked);

        Thread.sleep(6000);  // 等待线程1释放
    }

    /**
     * 测试3：tryLock - 等待指定时间
     */
    @Test
    void testTryLockWithWait() throws InterruptedException {
        RLock lock = redissonClient.getLock("test:wait");

        System.out.println("=== tryLock 等待测试 ===");

        // 线程1获取锁
        new Thread(() -> {
            lock.lock();
            System.out.println("线程1: 获取锁成功");
            try {
                Thread.sleep(3000);  // 持有锁3秒
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
                System.out.println("线程1: 释放锁");
            }
        }).start();

        Thread.sleep(100);  // 确保线程1先获取锁

        // 线程2等待5秒
        System.out.println("线程2: 开始等待，最多5秒...");
        boolean locked = lock.tryLock(5, TimeUnit.SECONDS);
        System.out.println("线程2: tryLock结果 = " + locked);

        if (locked) {
            lock.unlock();
        }
    }

    /**
     * 测试4：tryLock - 超时放弃
     */
    @Test
    void testTryLockTimeout() throws InterruptedException {
        RLock lock = redissonClient.getLock("test:timeout");

        System.out.println("=== tryLock 超时测试 ===");

        // 线程1获取锁，持有10秒
        new Thread(() -> {
            lock.lock();
            System.out.println("线程1: 获取锁成功，持有10秒");
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
                System.out.println("线程1: 释放锁");
            }
        }).start();

        Thread.sleep(100);

        // 线程2只等待2秒
        System.out.println("线程2: 开始等待，最多2秒...");
        boolean locked = lock.tryLock(2, TimeUnit.SECONDS);
        System.out.println("线程2: tryLock结果 = " + locked + "（超时放弃）");

        Thread.sleep(11000);  // 等待线程1完成
    }

    /**
     * 测试5：看门狗自动续期
     */
    @Test
    void testWatchdog() throws InterruptedException {
        RLock lock = redissonClient.getLock("test:watchdog");

        System.out.println("=== 看门狗测试 ===");
        System.out.println("锁默认过期时间: 30秒");
        System.out.println("看门狗每10秒续期一次");

        CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            // 不指定 leaseTime，启动看门狗
            lock.lock();
            System.out.println("线程1: 获取锁成功，看门狗启动");

            try {
                // 模拟长业务（60秒）
                for (int i = 1; i <= 6; i++) {
                    Thread.sleep(10000);
                    System.out.println("线程1: 业务执行中... " + (i * 10) + "秒");
                }
                System.out.println("线程1: 业务完成");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
                System.out.println("线程1: 释放锁，看门狗停止");
                latch.countDown();
            }
        }).start();

        // 35秒后尝试获取锁（如果无看门狗，此时锁已过期）
        Thread.sleep(35000);
        System.out.println("\n主线程: 尝试获取锁...");
        boolean locked = lock.tryLock(1, TimeUnit.SECONDS);
        System.out.println("主线程: 获取结果 = " + locked + "（看门狗续期中，无法获取）");

        latch.await();
    }

    /**
     * 测试6：读写锁
     */
    @Test
    void testReadWriteLock() throws InterruptedException {
        RReadWriteLock rwLock = redissonClient.getReadWriteLock("test:rw");
        RLock readLock = rwLock.readLock();
        RLock writeLock = rwLock.writeLock();

        System.out.println("=== 读写锁测试 ===");

        CountDownLatch latch = new CountDownLatch(3);

        // 线程1：读操作
        new Thread(() -> {
            readLock.lock();
            System.out.println("线程1: 获取读锁，开始读取...");
            try {
                Thread.sleep(3000);
                System.out.println("线程1: 读取完成");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                readLock.unlock();
                System.out.println("线程1: 释放读锁");
                latch.countDown();
            }
        }).start();

        // 线程2：读操作（可以并发读）
        new Thread(() -> {
            readLock.lock();
            System.out.println("线程2: 获取读锁，开始读取...");
            try {
                Thread.sleep(3000);
                System.out.println("线程2: 读取完成");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                readLock.unlock();
                System.out.println("线程2: 释放读锁");
                latch.countDown();
            }
        }).start();

        Thread.sleep(500);

        // 线程3：写操作（必须等读锁释放）
        new Thread(() -> {
            System.out.println("线程3: 等待写锁...");
            writeLock.lock();
            System.out.println("线程3: 获取写锁，开始写入...");
            try {
                Thread.sleep(2000);
                System.out.println("线程3: 写入完成");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                writeLock.unlock();
                System.out.println("线程3: 释放写锁");
                latch.countDown();
            }
        }).start();

        latch.await();
    }

    /**
     * 测试7：MultiLock（联锁）
     */
    @Test
    void testMultiLock() throws InterruptedException {
        RLock lock1 = redissonClient.getLock("test:resource:1");
        RLock lock2 = redissonClient.getLock("test:resource:2");
        RLock lock3 = redissonClient.getLock("test:resource:3");

        // 创建联锁，必须同时获取所有锁
        RedissonMultiLock multiLock = new RedissonMultiLock(lock1, lock2, lock3);

        System.out.println("=== MultiLock 联锁测试 ===");

        CountDownLatch latch = new CountDownLatch(2);

        // 线程1：获取联锁
        new Thread(() -> {
            System.out.println("线程1: 尝试获取联锁（3把锁）...");
            multiLock.lock();
            System.out.println("线程1: 获取联锁成功，持有所有锁");
            try {
                Thread.sleep(5000);
                System.out.println("线程1: 业务完成");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                multiLock.unlock();
                System.out.println("线程1: 释放所有锁");
                latch.countDown();
            }
        }).start();

        Thread.sleep(500);

        // 线程2：尝试获取其中一把锁
        new Thread(() -> {
            System.out.println("线程2: 尝试获取 lock2...");
            boolean locked = false;
            try {
                locked = lock2.tryLock(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("线程2: lock2 获取结果 = " + locked + "（被线程1持有）");
            if (locked) {
                lock2.unlock();
            }
            latch.countDown();
        }).start();

        latch.await();
    }

    /**
     * 测试8：并发场景模拟一人一单
     */
    @Test
    void testConcurrentSeckill() throws InterruptedException {
        RLock lock = redissonClient.getLock("test:seckill:user:1");
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);

        System.out.println("=== 并发一人一单测试 ===");
        System.out.println("5个线程同时抢购...\n");

        for (int i = 1; i <= 5; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    boolean locked = lock.tryLock(10, 10, TimeUnit.SECONDS);
                    if (locked) {
                        try {
                            System.out.println("线程" + threadNum + ": 获取锁成功，下单中...");
                            Thread.sleep(1000);  // 模拟下单
                            System.out.println("线程" + threadNum + ": 下单成功！");
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        System.out.println("线程" + threadNum + ": 获取锁失败，请勿重复下单");
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
    }
}
