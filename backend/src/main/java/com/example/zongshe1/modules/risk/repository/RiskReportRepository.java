package com.example.zongshe1.modules.risk.repository;

import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.risk.entity.RiskReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 风控报告数据访问层接口
 */
@Repository
public interface RiskReportRepository extends JpaRepository<RiskReport, Long> {

    /**
     * 根据贷款申请查询风控报告
     * @param loanApplication 贷款申请实体
     * @return 风控报告
     */
    Optional<RiskReport> findByLoanApplication(LoanApplication loanApplication);

    Optional<RiskReport> findByLoanApplication_Id(Long applicationId);

    // Only locks an existing report; first inserts still rely on the application unique constraint.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RiskReport r where r.loanApplication.id = :applicationId")
    Optional<RiskReport> findForAssessment(@Param("applicationId") Long applicationId);
}
