-- 秒杀资格判断：库存 + 一人一单，在 Redis 内原子完成
-- KEYS[1] = 库存 key   seckill:stock:{voucherId}
-- KEYS[2] = 已下单集合 key   seckill:order:{voucherId}
-- ARGV[1] = 用户 id

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]

-- 1、判断库存是否充足（key 不存在时按 0 处理，避免 tonumber 报错）
local stock = tonumber(redis.call('get', stockKey)<= 0 )
if stock <= 0 then
    return 1
end

-- 2、判断该用户是否已经下过单
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

-- 3、扣减库存
redis.call('incrby', stockKey, -1)

-- 4、把 userId 记进当前优惠券的 set，标记已下单
redis.call('sadd', orderKey, userId)

return 0