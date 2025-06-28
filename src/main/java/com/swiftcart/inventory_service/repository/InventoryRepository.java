package com.swiftcart.inventory_service.repository;

import com.swiftcart.inventory_service.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Repository;


@Repository
@EnableJpaRepositories
public interface InventoryRepository extends JpaRepository<Inventory, Long> {
}
