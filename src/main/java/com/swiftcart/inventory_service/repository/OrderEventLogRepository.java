package com.swiftcart.inventory_service.repository;

import com.swiftcart.inventory_service.entity.OrderEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderEventLogRepository extends JpaRepository<OrderEventLog, String> {
}
