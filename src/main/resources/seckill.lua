-- 1. 参数列表
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

-- 2. Redis key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local streamKey = 'stream:orders'

-- 3. 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，返回 1
    return 1
end

-- 4. 判断用户是否已下单（set 集合中是否包含 userId）
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 已下单，返回 2
    return 2
end

-- 5. 扣减库存，将 userId 存入 set 集合
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

-- 6. 发送消息到 Redis Stream（订单 ID、用户 ID、优惠券 ID）
redis.call('xadd', streamKey, '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

-- 7. 返回 0，表示成功
return 0
