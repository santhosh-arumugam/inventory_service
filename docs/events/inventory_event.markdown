# InventoryEvent

Published by Inventory Service after processing OrderCreatedEvent.

## Schema
- **version**: Integer, event schema version (e.g., 1).
- **requestId**: String, UUID from OrderCreatedEvent.
- **orderId**: String, order identifier.
- **items**: Array of objects:
  - **productId**: Long, product identifier.
  - **qty**: Integer, quantity reserved.
- **ts**: String, UTC timestamp (ISO 8601).
- **eventType**: String, "STOCK_RESERVED" or "STOCK_FAILED".
- **status**: String, "SUCCESS" or "FAILED".
- **reason**: String, failure reason (if applicable).

## Example
```json
{
  "version": 1,
  "requestId": "d46dff95-96f7-4002-83b6-05e73bc97dfe",
  "orderId": "16",
  "items": [
    {
      "productId": 123,
      "qty": 2
    }
  ],
  "ts": "2025-05-04T12:00:01Z",
  "eventType": "STOCK_RESERVED",
  "status": "SUCCESS",
  "reason": null
}
```