-- KEYS[1]: Stock hash key
-- ARGV[1]: Requested quantity
local stock_key = KEYS[1]
local requested_qty = tonumber(ARGV[1])

-- Get current stock
local current_stock = redis.call('HGET', stock_key, 'quantity')
if not current_stock then return {0, 0} -- Stock not found
    end
    current_stock = tonumber(current_stock)

-- Check if enough stock
if current_stock >= requested_qty then
    -- Decrement stock
    redis.call('HSET', stock_key, 'quantity', current_stock - requested_qty)
    return {1, current_stock - requested_qty}    -- Success, new stock
    else
        return {0, current_stock} -- Insufficient stock end