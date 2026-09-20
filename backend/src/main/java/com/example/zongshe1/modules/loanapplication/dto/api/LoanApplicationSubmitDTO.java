package com.example.zongshe1.dto.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 综设 III 申贷提交请求 DTO
 */
@Data
public class LoanApplicationSubmitDTO {

    @NotNull(message = "产品ID不能为空")
    private Long productId;

    @NotNull(message = "申请金额不能为空")
    @DecimalMin(value = "0.01", message = "申请金额必须大于0")
    private BigDecimal amount;

    @NotNull(message = "申请期限不能为空")
    private Integer termMonths;

    private String usage; // 消费用途（如：装修、购买家电、日常消费等）
}