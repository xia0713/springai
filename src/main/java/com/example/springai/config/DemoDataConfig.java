package com.example.springai.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * NL2SQL 演示表（Day52）：demo_orders 建表 + 种子数据。
 * <p>
 * 幂等：表存在且已有数据则跳过，重启不重灌。
 */
@Configuration
public class DemoDataConfig {

    @Bean
    public CommandLineRunner initDemoOrders(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS demo_orders (
                    order_id   VARCHAR(20) PRIMARY KEY,
                    product    VARCHAR(50),
                    amount     NUMERIC(10,2),
                    quantity   INT,
                    status     VARCHAR(20),
                    order_date DATE
                )
                """);
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM demo_orders", Integer.class);
            if (count != null && count > 0) {
                return;   // 已有数据，跳过
            }
            jdbc.batchUpdate("""
                INSERT INTO demo_orders (order_id, product, amount, quantity, status, order_date)
                VALUES (?, ?, ?, ?, ?, ?)
                """, List.of(
                    new Object[]{"ORD-001", "蓝牙耳机", 299.00, 2, "已签收", LocalDate.of(2026, 8, 10)},
                    new Object[]{"ORD-002", "智能手表", 1299.00, 1, "已签收", LocalDate.of(2026, 8, 12)},
                    new Object[]{"ORD-003", "蓝牙耳机", 299.00, 1, "已发货", LocalDate.of(2026, 8, 20)},
                    new Object[]{"ORD-004", "运动鞋",   459.00, 1, "待发货", LocalDate.of(2026, 8, 25)},
                    new Object[]{"ORD-005", "键盘",     399.00, 2, "已发货", LocalDate.of(2026, 9, 1)},
                    new Object[]{"ORD-006", "蓝牙耳机", 299.00, 3, "已取消", LocalDate.of(2026, 9, 2)},
                    new Object[]{"ORD-007", "鼠标",     129.00, 5, "已发货", LocalDate.of(2026, 9, 3)},
                    new Object[]{"ORD-008", "智能手表", 1299.00, 2, "待发货", LocalDate.of(2026, 9, 5)},
                    new Object[]{"ORD-009", "显示器",   1899.00, 1, "已签收", LocalDate.of(2026, 9, 6)},
                    new Object[]{"ORD-010", "键盘",     399.00, 1, "已签收", LocalDate.of(2026, 9, 7)}
            ));
            System.out.println(">>> demo_orders 种子数据已灌入 10 条");
        };
    }
}
