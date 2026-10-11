package com.example.zongshe1.modules.ops.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关键操作审计。第四周只记登录和创建申请。
 */
@Entity
@Table(name = "ops_audit_log", indexes = {
        @Index(name = "idx_ops_audit_occurred", columnList = "occurred_at")
})
@Data
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(nullable = false, length = 255)
    private String path;

    @Column(name = "result_code", nullable = false)
    private Integer resultCode;
}
