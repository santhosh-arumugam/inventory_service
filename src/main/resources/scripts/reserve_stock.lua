local stock_key = KEYS[1]
local quantity = tonumber(ARGV[1])
local current = tonumber(redis.call("GET", stock_key) or 0)
if current >= quantity then
    redis.call("DECRBY", stock_key, quantity)
    return 1
else
    return 0
end