# 黑马点评后端 · hm-dianping

基于 Spring Boot、MyBatis-Plus、MySQL 和 Redis 的本地生活点评学习项目，包含店铺查询、探店笔记、关注、签到与优惠券秒杀等后端代码。

本仓库主要提供 Java 后端，没有配套前端工程。功能说明以当前代码为准，不代表已完成生产环境验证。

## 功能

| 模块 | 代码中的功能 |
| --- | --- |
| 用户 | 验证码登录、退出、当前用户、用户资料查询 |
| 店铺 | 店铺分类、详情、名称/类型查询、新增与修改、缓存处理 |
| 探店笔记 | 发布、详情、热门列表、点赞、点赞用户、关注动态 |
| 关注 | 关注/取消关注、关注状态、共同关注 |
| 签到 | Redis 签到记录与连续签到统计 |
| 优惠券 | 普通券、秒杀券、店铺优惠券列表 |
| 秒杀订单 | Lua 校验、Redis Stream 异步订单处理、Redisson 锁与数据库落单 |
| 图片 | 阿里云 OSS 图片上传 |

`BlogCommentsController` 当前只有控制器壳，没有评论业务接口，不应视为已实现完整评论功能。

## 技术栈

- Java 8 编译目标，Spring Boot 2.7.18。
- MyBatis-Plus、MySQL。
- Spring Data Redis、Lettuce、Redisson、Redis Lua 与 Stream。
- Hutool、Lombok、阿里云 OSS SDK。

## 目录

```text
src/main/java/com/hmdp/
├─ controller/       HTTP 接口
├─ service/impl/     业务实现
├─ mapper/           数据访问
└─ utils/            缓存、令牌、锁等工具
src/main/resources/
├─ application.yaml  应用配置
├─ db/hmdp.sql       数据库脚本
├─ seckill.lua       秒杀脚本
└─ unlock.lua        锁处理脚本
```

## 准备环境

1. 安装与 Java 8 编译目标兼容的 JDK、Maven。
2. 准备 MySQL 和支持 Stream 命令的 Redis。
3. 创建 `hmdp` 数据库，并导入 [数据库脚本](src/main/resources/db/hmdp.sql)。
4. 按实际环境调整 [application.yaml](src/main/resources/application.yaml)：
   - `spring.datasource`：数据库连接。
   - `spring.redis`：Redis 地址、端口和认证。
   - `hmdp.alioss`：图片上传需要的 OSS 配置。

不要复用或公开真实的数据库密码、Redis 密码和云访问密钥；请使用不提交的本地配置或外部配置覆盖。

## 启动

```shell
git clone https://github.com/Trappedqueens/hm-dianping.git
cd hm-dianping
mvn spring-boot:run
```

默认端口为 **8081**，API 基址为 `http://localhost:8081`。根路径没有提供前端首页，直接访问根地址不能用于判断全部接口是否正常。

也可以构建后运行：

```shell
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

## 常用接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/user/code?phone=...` | 生成验证码 |
| POST | `/user/login` | 验证码登录 |
| GET | `/user/me` | 当前登录用户 |
| GET | `/shop/{id}` | 店铺详情 |
| GET | `/shop-type/list` | 店铺分类 |
| GET | `/blog/hot` | 热门笔记 |
| PUT | `/blog/like/{id}` | 点赞或取消点赞 |
| PUT | `/follow/{id}/{isFollow}` | 关注或取消关注 |
| POST | `/voucher-order/seckill/{id}` | 秒杀下单 |

完整参数与接口见 [controller](src/main/java/com/hmdp/controller)。

登录后按当前拦截器实现，在 `authorization` 请求头中携带登录返回的令牌。不要自行假设它使用标准 JWT Bearer 格式。

## 实现说明

- 当前验证码由服务端生成、存入 Redis 并打印到控制台，不等于已接入真实短信平台。
- 秒杀流程依赖 Lua、Redis Stream 消费组、Redis 库存数据和对应数据库记录；仅启动进程不代表秒杀数据已准备完毕。
- Redis 地址与 Redisson 配置应保持一致，请同时检查相关配置类。
- [测试目录](src/test/java/com/hmdp)包含锁、Stream 与应用测试，部分依赖外部服务，执行前请使用独立测试数据。

本次仅补充 README，未修改业务代码，未运行构建、数据库导入或接口测试。
