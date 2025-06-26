package com.swiftcart.inventory_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class OrderEventLog {

    @Id
    private String requestId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String eventType;
}
