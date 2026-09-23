package com.example.zongshe1.modules.loanapplication.repository;

import com.example.zongshe1.modules.loanapplication.entity.Contract;
import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 合同数据访问层接口
 */
@Repository
public interface ContractRepository extends JpaRepository<Contract, Long> {

    /**
     * 根据合同编号查询
     * @param contractNo 合同编号
     * @return 合同
     */
    Optional<Contract> findByContractNo(String contractNo);

    /**
     * 根据贷款申请查询合同
     * @param loanApplication 贷款申请
     * @return 合同
     */
    Optional<Contract> findByLoanApplication(LoanApplication loanApplication);

    /**
     * 根据合同状态查询
     * @param contractStatus 合同状态
     * @return 合同列表
     */
    java.util.List<Contract> findByContractStatus(Integer contractStatus);
}