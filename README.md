# 优品秒杀 · 本地生活优惠券秒杀系统（后端）

本地生活类优惠券营销系统的后端实现，核心解决两个问题：**商户信息的缓存一致性** 与 **优惠券秒杀场景下的高并发控制**。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 核心框架 | Spring Boot 2.3.12、Spring MVC |
| 持久层 | MyBatis-Plus 3.4.3、MySQL 5.7+ |
| 缓存 | Redis（Lettuce 连接池） |
| 分布式锁 | Redisson 3.13.6、自研 Redis 分布式锁（Lua 脚本释放） |
| 工具 | Hutool、Lombok、AOP |

## 已实现功能

### 1. 登录认证
- 手机号 + 验证码登录，验证码写入 Redis 并设置过期时间
- 登录成功后生成随机 Token 作为 Redis Hash 的 Key，存储用户信息，返回前端
- 双拦截器体系：`RefreshTokenInterceptor` 负责刷新 Token 有效期，`LoginInterceptor` 负责登录校验；拦截器通过 `UserHolder`（ThreadLocal）向业务层传递当前用户

### 2. 商户信息缓存
针对缓存三大问题分别落地解决方案，每种方案都保留独立实现以便对比：

| 问题 | 方案 | 实现位置 |
| --- | --- | --- |
| 缓存穿透 | 空值缓存：数据库不存在的 ID 写入空字符串并设置较短 TTL | `queryWithPassThrough` |
| 缓存击穿（方案一） | 互斥锁：基于自研 Redis 分布式锁串行化缓存重建 | `queryWithMutex` |
| 缓存击穿（方案二） | 逻辑过期：缓存不设物理 TTL，用线程池异步重建 | `queryWithLogicalExpire` |
| 缓存雪崩 | TTL 叠加随机值打散过期时间 | `CACHE_SHOP_TTL + RandomUtil.randomLong(0, 10)` |

- 商铺更新采用**先更新数据库、事务提交后再删除缓存**的策略，通过 `TransactionSynchronization` 的 `afterCommit` 回调保证缓存删除时机正确

### 3. 优惠券秒杀
- 基于 **Redisson 分布式锁**，按 `lock:order:{userId}` 维度加锁，保证同一用户并发请求只有一个能进入下单逻辑，实现一人一单
- 通过 `AopContext.currentProxy()` 获取当前代理对象再调用下单方法，解决**类内部方法调用导致 `@Transactional` 失效**的问题
- 库存扣减与订单落库在同一事务内完成，扣减条件带 `stock > 0` 乐观校验，防止超卖

### 4. 自研 Redis 分布式锁
- `SimpleRedisLock` 基于 `SETNX + 过期时间` 实现，解决「获取锁后服务宕机导致死锁」
- 释放锁时用 **Lua 脚本**原子执行「判断锁是否属于当前线程 + 删除」，避免误删他人持有的锁

## 规划中 / 未完成

以下模块保留了实体、Mapper 与 Controller 骨架，业务逻辑尚未实现：

- 达人探店笔记与点赞（`BlogServiceImpl`）
- 好友关注与共同关注（`FollowServiceImpl`）
- 附近商铺 GEO 检索
- 用户签到（BitMap）与 UV 统计（HyperLogLog）

## 项目结构

```
hm-dianping
├── sql/                     # 建库脚本
└── src/main
    ├── java/com/hmdp
    │   ├── config/          # Redisson、MyBatis-Plus、MVC 拦截器配置
    │   ├── controller/      # 商户、优惠券、秒杀订单、用户、上传
    │   ├── service/impl/    # 业务实现（缓存与秒杀核心逻辑）
    │   ├── utils/           # CacheClient、SimpleRedisLock、RedisIdWorker 等
    │   ├── dto/ entity/     # 数据传输对象与实体
    │   └── mapper/          # 数据访问
    └── resources
        ├── application.yaml
        └── unLock.lua       # 释放分布式锁的 Lua 脚本
```

## 本地运行

### 环境要求
- JDK 8+
- MySQL 5.7+
- Redis 5.0+

### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE hmdp DEFAULT CHARSET utf8mb4;"
mysql -uroot -p hmdp < sql/hmdp.sql
```

### 2. 修改配置

`src/main/resources/application.yaml` 中按本地环境修改数据库与 Redis 连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC
    username: root
    password: 你的密码
  redis:
    host: localhost
    port: 6379
```

### 3. 启动

```bash
mvn spring-boot:run
```

服务端口 `8081`。

## 声明

本项目的业务模型参考公开教学案例，**代码为本人独立完成**，并在分布式锁自研实现、缓存击穿双方案对比、事务失效问题处理等处做了补充与注释。
