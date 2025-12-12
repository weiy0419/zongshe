package com.example.zongshe1.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "t_loan_application")
public class LoanApplication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_no", length = 64, unique = true)
    private String applicationNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "loan_amount")
    private BigDecimal loanAmount;

    @Column(name = "loan_term")
    private Integer loanTerm;

    @Column(name = "app_status")
    private Integer appStatus;

    @OneToOne(mappedBy = "loanApplication", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private Contract contract;

    @OneToOne(mappedBy = "loanApplication", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private RiskReport riskReport;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public void setApplicationNo(String applicationNo) {
        this.applicationNo = applicationNo;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public BigDecimal getLoanAmount() {
        return loanAmount;
    }

    public void setLoanAmount(BigDecimal loanAmount) {
        this.loanAmount = loanAmount;
    }

    public Integer getLoanTerm() {
        return loanTerm;
    }

    public void setLoanTerm(Integer loanTerm) {
        this.loanTerm = loanTerm;
    }

    public Integer getAppStatus() {
        return appStatus;
    }

    public void setAppStatus(Integer appStatus) {
        this.appStatus = appStatus;
    }

    public Contract getContract() {
        return contract;
    }

    public void setContract(Contract contract) {
        this.contract = contract;
    }

    public RiskReport getRiskReport() {
        return riskReport;
    }

    public void setRiskReport(RiskReport riskReport) {
        this.riskReport = riskReport;
    }
}