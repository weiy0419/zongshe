package com.example.zongshe1.repository;

import com.example.zongshe1.modules.loanapplication.entity.Contract;
import com.example.zongshe1.modules.loanapplication.entity.DisburseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DisburseRecordRepository extends JpaRepository<DisburseRecord, Long> {

    List<DisburseRecord> findByContract(Contract contract);

    boolean existsByContractAndDisburseStatus(Contract contract, Integer disburseStatus);
}
