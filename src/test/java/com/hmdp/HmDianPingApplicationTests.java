package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ShopTypeMapper shopTypeMapper;
    @Resource
    private IUserService userService;

    private static final Long TEST_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(TEST_USER_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void testSaveShopToRedis() {
        // 预热缓存：将店铺ID为1的数据以逻辑过期方式存入Redis
        shopService.saveShop2Redis(1L, 30L);
        System.out.println("缓存预热成功！");
    }

    @Test
    void testIdWorker() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            long id = redisIdWorker.nextId("order");
            System.out.println("id = " + id);
        }
    }

    @Test
    void testIdWorkerValidation() {
        long beginTimestamp = 1640995200L; // 2022-01-01 00:00:00 UTC

        for (int i = 0; i < 10; i++) {
            long id = redisIdWorker.nextId("test");

            // 提取高32位时间戳
            long timestamp = id >>> 32;
            // 提取低32位计数器
            long count = id & 0xFFFFFFFFL;

            // 验证时间戳
            long actualTimestamp = timestamp + beginTimestamp;
            long nowSecond = System.currentTimeMillis() / 1000;
            long diff = Math.abs(nowSecond - actualTimestamp);

            System.out.printf("ID: %d, 时间戳: %d, 计数器: %d, 时间差: %d秒%n",
                    id, actualTimestamp, count, diff);

            // 时间差应该在合理范围内（比如5秒内）
            assert diff < 5 : "时间戳偏差过大: " + diff + "秒";
            // 计数器应该递增且大于0
            assert count > 0 : "计数器应该大于0";
        }
    }

    @Test
    void testImportShopGeoByType() {
        // 查询所有店铺
        List<Shop> allShops = shopService.list();
        // 查询所有店铺类型
        List<ShopType> shopTypes = shopTypeMapper.selectList(null);

        // 按 typeId 分组
        Map<Long, List<Shop>> groupedByType = allShops.stream()
                .filter(shop -> shop.getX() != null && shop.getY() != null)
                .collect(Collectors.groupingBy(Shop::getTypeId));

        // 清除旧缓存，重新导入
        for (ShopType type : shopTypes) {
            String key = RedisConstants.SHOP_GEO_KEY + type.getId();
            stringRedisTemplate.delete(key);

            List<Shop> shopsInType = groupedByType.get(type.getId());
            if (shopsInType == null || shopsInType.isEmpty()) {
                System.out.println("类型[" + type.getName() + "] typeId=" + type.getId() + " 无店铺数据，跳过");
                continue;
            }

            // 批量 GEOADD
            for (Shop shop : shopsInType) {
                stringRedisTemplate.opsForGeo().add(key,
                        new Point(shop.getX(), shop.getY()),
                        shop.getId().toString());
            }

            System.out.println("类型[" + type.getName() + "] typeId=" + type.getId()
                    + " 导入 " + shopsInType.size() + " 条店铺Geo数据");
        }

        System.out.println("Redis Geo 按typeId分类缓存导入完成！");
    }

    @Test
    void testNearbyShopSearch() {
        // 查所有店铺坐标
        List<Shop> shops = shopService.list();
        // 随机选一个有坐标的店铺作为中心点进行Geo搜索
        Shop center = shops.stream()
                .filter(s -> s.getX() != null && s.getY() != null)
                .findFirst().orElse(null);
        if (center == null) {
            System.out.println("无带坐标店铺，跳过");
            return;
        }
        System.out.printf("中心点: %s (x=%.4f, y=%.4f)%n", center.getName(), center.getX(), center.getY());
        Result result = shopService.queryShopByType(center.getTypeId().intValue(), 1, center.getX(), center.getY());
        System.out.println("success=" + result.getSuccess());
        @SuppressWarnings("unchecked")
        List<Shop> nearby = (List<Shop>) result.getData();
        if (nearby != null) {
            nearby.forEach(s -> System.out.printf("  %s 距离: %.0fm%n", s.getName(), s.getDistance()));
        }
    }

    @Test
    void testSignAndCount() {
        // 清理旧数据
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + TEST_USER_ID + time;
        stringRedisTemplate.delete(key);

        // 测试签到
        Result signResult = userService.sign();
        System.out.println("sign() result: success=" + signResult.getSuccess());

        // 验证Redis中的bit已设置
        int dayOfMonth = LocalDateTime.now().getDayOfMonth();
        Boolean bit = stringRedisTemplate.opsForValue().getBit(key, dayOfMonth - 1);
        System.out.println("key=" + key + " bit[" + (dayOfMonth - 1) + "]=" + bit);

        // 测试连续签到统计
        Result countResult = userService.constantSign();
        System.out.println("constantSign() result: success=" + countResult.getSuccess() + " data=" + countResult.getData());

        // 模拟连续多天签到
        System.out.println("\n=== 模拟签到前5天===");
        for (int day = 1; day <= 5; day++) {
            stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        }
        countResult = userService.constantSign();
        System.out.println("签到1-5天后 constantSign()=" + countResult.getData());

        // 清理
        stringRedisTemplate.delete(key);
    }

    @Test
    void testSignKeyDebug() {
        // 检查 sign() 和 constantSign() 使用的Redis key
        // sign() 中的 key 格式: sign:{userId}:yyyyMM
        String signKey = RedisConstants.USER_SIGN_KEY + TEST_USER_ID + ":202606";
        // constantSign() 中的 key 格式: sign::{userId}yyyyMM (多一个冒号)
        String constKey = RedisConstants.USER_SIGN_KEY + ":" + TEST_USER_ID + "202606";

        System.out.println("sign()          写入的key: " + signKey);
        System.out.println("constantSign()  读取的key: " + constKey);
        System.out.println("key是否匹配: " + signKey.equals(constKey));

        // 验证sign()写入的数据在哪个key下
        System.out.println("\n=== Redis中的实际数据 ===");
        Boolean bitInSignKey = stringRedisTemplate.opsForValue().getBit(signKey, 0);
        Boolean bitInConstKey = stringRedisTemplate.opsForValue().getBit(constKey, 0);
        System.out.println(signKey + " bit0=" + bitInSignKey);
        System.out.println(constKey + " bit0=" + bitInConstKey);
    }

    @Test
    void contextLoads() {
    }
}
