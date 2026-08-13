package com.hmdp;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class SeckillStreamTest {

    private final String BASE_URL = "http://localhost:8081";
    private final long VOUCHER_ID = 21L;

    /**
     * 批量登录获取token
     */
    @Test
    public void testLoginAndGetTokens() {
        String[] phones = {
            "13838411438", "13456789011", "13456789001",
            "13456762069", "13688668889", "13688668890",
            "13688668891", "13688668892", "13688668893"
        };

        List<String> tokens = new ArrayList<>();

        for (String phone : phones) {
            // 发验证码
            HttpRequest.post(BASE_URL + "/user/code?phone=" + phone).execute();
            // 验证码已写入redis，格式为 login:code:{phone}
            // 需要从redis读取，这里用Spring的StringRedisTemplate
        }

        // 等验证码写入
        try { Thread.sleep(1000); } catch (Exception e) {}

        // 用RedisTemplate读取验证码并登录
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
            ApplicationContextHelper.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);

        for (String phone : phones) {
            String code = redisTemplate.opsForValue().get("login:code:" + phone);
            if (code == null) {
                System.out.println("验证码未找到: " + phone);
                continue;
            }
            String body = JSONUtil.createObj()
                .set("phone", phone)
                .set("code", code)
                .toString();

            HttpResponse resp = HttpRequest.post(BASE_URL + "/user/login")
                .header("Content-Type", "application/json")
                .body(body)
                .execute();

            String respBody = resp.body();
            if (respBody.contains("\"data\":")) {
                String token = JSONUtil.parseObj(respBody).getStr("data");
                tokens.add(token);
                System.out.println("Phone: " + phone + " -> Token: " + token);
            } else {
                System.out.println("登录失败: " + phone + " -> " + respBody);
            }
        }

        System.out.println("\n=== 获取到 " + tokens.size() + " 个token ===");
        tokens.forEach(System.out::println);
    }

    /**
     * 多账号并发秒杀
     */
    @Test
    public void testConcurrentSeckill() throws Exception {
        // 先批量登录获取token
        String[] phones = {
            "13838411438", "13456789011", "13456789001",
            "13456762069", "13688668889", "13688668890",
            "13688668891", "13688668892", "13688668893"
        };

        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
            ApplicationContextHelper.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);

        List<String> tokens = new ArrayList<>();
        for (String phone : phones) {
            HttpRequest.post(BASE_URL + "/user/code?phone=" + phone).execute();
        }
        Thread.sleep(1500);

        for (String phone : phones) {
            String code = redisTemplate.opsForValue().get("login:code:" + phone);
            if (code == null) continue;
            String body = JSONUtil.createObj().set("phone", phone).set("code", code).toString();
            HttpResponse resp = HttpRequest.post(BASE_URL + "/user/login")
                .header("Content-Type", "application/json").body(body).execute();
            String token = JSONUtil.parseObj(resp.body()).getStr("data");
            if (token != null) tokens.add(token);
        }

        System.out.println("获取到 " + tokens.size() + " 个token，开始并发秒杀...");

        int threadCount = tokens.size();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        long start = System.currentTimeMillis();

        for (String token : tokens) {
            executor.submit(() -> {
                try {
                    HttpResponse resp = HttpRequest.post(BASE_URL + "/voucher-order/seckill/" + VOUCHER_ID)
                        .header("authorization", token)
                        .timeout(10000)
                        .execute();
                    String body = resp.body();
                    if (body.contains("\"success\":true")) {
                        successCount.incrementAndGet();
                        results.add("SUCCESS: " + body);
                    } else {
                        failCount.incrementAndGet();
                        results.add("FAIL: " + body);
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    results.add("ERROR: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long cost = System.currentTimeMillis() - start;

        System.out.println("\n========== 测试结果 ==========");
        System.out.println("并发数: " + threadCount);
        System.out.println("成功: " + successCount.get());
        System.out.println("失败: " + failCount.get());
        System.out.println("耗时: " + cost + "ms");
        System.out.println("\n--- 详细结果 ---");
        results.forEach(System.out::println);
    }
}
