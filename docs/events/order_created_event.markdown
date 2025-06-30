# OrderCreatedEvent

Published by Order Service when a new order is created.

## Schema
- **version**: Integer, event schema version (e.g., 1).
- **requestId**: String, UUID for idempotency.
- **orderId**: String, unique order identifier.
- **userId**: String, customer identifier.
- **items**: Array of objects:
  - **productId**: Long, product identifier.
  - **qty**: Integer, quantity ordered.
- **ts**: String, UTC timestamp (ISO 8601, e.g., "2025-05-04T12:00:00Z").

## Example
```json
{
  "version": 1,
  "requestId": "d46dff95-96f7-4002-83b6-05e73bc97dfe",
  "orderId": "16",
  "userId": "1002",
  "items": [
    {
      "productId": 123,
      "qty": 2
    }
  ],
  "ts": "2025-05-04T12:00:00Z"
}
```