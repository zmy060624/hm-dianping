# 优品秒杀 · 本地生活优惠券秒杀系统（后端）

本地生活类优惠券营销系统的后端实现，核心解决两个问题：**商户信息的缓存一致性** 与 **优惠券秒杀场景下的高并发控制**。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 核心框架 | Spring Boot 2.3.12、Spring MVC |
| 持久层 | MyBatis-Plus 3.4.3、MySQL 5.7+ |
| 缓存 | Redis（Lettuce 连接池） |
| 分布式锁 | Redisson 3.13.6、自研 Redis 分布式锁（Lua 脚本释放） |
| 接口文档 | Knife4j 3.0.3（springfox 3.0.0），在线调试 |
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

## 接口文档

集成 Knife4j，启动后访问：

```
http://localhost:8081/doc.html
```

- 左侧按 Controller 自动分组，共 18 个接口分组，可在线填参调试
- 需要登录态的接口会返回 401：在「文档管理 → 全局参数」中添加请求头 `authorization`，值为登录接口返回的 Token
- 文档相关的静态资源已在 `MvcConfig.excludePathPatterns` 中放行，否则会被登录拦截器拦下

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

## 踩坑记录

开发过程中遇到的几个真实问题，记录排查思路供参考。

**1. 启动报 `A component required a bean of type 'RedissonClient' that could not be found`**

顺着异常栈回溯到 `VoucherOrderServiceImpl` 的 `@Resource` 注入点，再检查 `RedissonConfig`，发现工厂方法上漏了 `@Bean`。
`@Configuration` 只负责标记配置类，不会把类里的方法自动注册为 Bean——方法上没有 `@Bean` 就只是一个普通实例方法，容器里自然找不到这个 Bean。

**2. 补上 `@Bean` 后立刻变成 `Redis url should start with redis://`**

`setAddress()` 底层走 URI 解析，scheme 后面必须跟 `//`，写成 `redis:localhost:6379` 是非法的。
值得记一笔的是：**因为之前方法根本没被执行，这个错误一直被掩盖着**。配置类里的方法不执行，不代表配置正确。两层问题要一层层扒。

**3. 接口文档打不开，跳登录页或 404**

`LoginInterceptor` 拦的是 `/**`，Knife4j 的文档页和静态资源也在拦截范围内。
解决：在 `MvcConfig` 的 `excludePathPatterns` 中放行 `/doc.html`、`/webjars/**`、`/v2/api-docs`、`/swagger-resources/**`、`/favicon.ico`。

**4. Spring Boot 2.6+ 与 springfox 3.0.0 不兼容（另一个项目的经验）**

在 Spring Boot 2.7.3 项目里集成同类组件时，启动会抛 `Failed to start bean 'documentationPluginsBootstrapper'`。
原因是 2.6 起默认改用 `PathPatternParser`，而 springfox 只认 `AntPathMatcher`。在 `application.yml` 中补一行即可：

```yaml
spring:
  mvc:
    pathmatch:
      matching-strategy: ant_path_matcher
```

## 声明

本项目的业务模型参考公开教学案例，**代码为本人独立完成**，并在分布式锁自研实现、缓存击穿双方案对比、事务失效问题处理等处做了补充与注释。
