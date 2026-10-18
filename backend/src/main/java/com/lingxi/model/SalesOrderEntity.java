package com.lingxi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "sales_orders", indexes = {
        @Index(name = "idx_sales_quarter", columnList = "quarter"),
        @Index(name = "idx_sales_region", columnList = "region")
})
public class SalesOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(nullable = false, length = 16)
    private String region;

    @Column(nullable = false, length = 16)
    private String quarter;

    @Column(nullable = false, length = 16)
    private String channel;

    @Column(nullable = false, length = 64)
    private String product;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 128)
    private String customer;

    @Column(nullable = false, length = 32)
    private String owner;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;
}
