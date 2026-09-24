package com.example.zongshe1.common.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class RiskAssessmentRequest {

    private Long applicationId;
    private Long userId;
    private String applicantName;
    private String idCardNumber;
    private String phoneNumber;
    private String loanType;
    private String productType;
    private BigDecimal loanAmount;
    private Integer loanTerm;
    private String usage;
    private String channelId;
    private String description;

    /** 可选；旧申请未保存产品关联时，由调用方提供真实产品 ID 以启用去重。 */
    private Long productId;

    /** 可选；null 默认 WOE，空集合表示不执行授信模型。 */
    private List<String> enabledModels;

}
