-- KEYS[1]: Stock hash key (e.g., stock:productId:123)
-- ARGV[1]: Requested quantity
local stock_key = KEYS[1]
local requested_qty = tonumber(ARGV[1])

-- Get current stock
local current_stock = redis.call('HGET', stock_key, 'quantity')
if not current_stock then
return {0, 0}
end
current_stock = tonumber(current_stock)

-- Check if enough stock
if current_stock >= requested_qty then
-- Decrement stock
local new_stock = current_stock - requested_qty
redis.call('HSET', stock_key, 'quantity', new_stock)
return {1, new_stock}
else
return {0, current_stock}
end