package com.example.zongshe1.modules.repay.dto;

import lombok.Data;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.repay.entity.RepayPlan;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RepayRequestDTO {
    private Long id;
    private String repayNo;
    private RepayPlan repayPlan;
    private LoanApplication loanApplication;
    private BigDecimal amount;
    private LocalDateTime repayTime;
    private String repayMethod;
    private String transactionId;
    private String remark;
    private LocalDateTime createdAt;
}