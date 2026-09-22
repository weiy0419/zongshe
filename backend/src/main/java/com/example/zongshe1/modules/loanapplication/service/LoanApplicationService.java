package com.example.zongshe1.service;

import com.example.zongshe1.dto.LoanApplicationDTO;
import com.example.zongshe1.dto.api.LoanApplicationSubmitDTO;

import java.util.List;
import java.util.Map;

public interface LoanApplicationService {

    List<LoanApplicationDTO> getApplications(String status, String loanType, String search);

    LoanApplicationDTO getApplicationById(Long id);

    boolean approveApplication(Long id, String remark);

    boolean rejectApplication(Long id, String remark);

    Map<String, Object> getStatistics();

    /**
     * 提交贷款申请（标准重构版）
     * @param userId 用户ID
     * @param dto 申贷提交请求DTO
     * @return 申请结果
     */
    Map<String, Object> submitApplication(String userId, LoanApplicationSubmitDTO dto);

    /**
     * 根据用户ID查询贷款申请列表
     * @param userId 用户ID
     * @return 贷款申请列表
     */
    List<LoanApplicationDTO> getApplicationsByUserId(String userId);
}