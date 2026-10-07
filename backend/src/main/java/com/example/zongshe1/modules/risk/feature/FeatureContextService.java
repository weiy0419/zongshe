package com.example.zongshe1.modules.risk.feature;

import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.risk.datasource.DataSourceQuery;
import com.example.zongshe1.modules.risk.datasource.ExternalDataFetchResult;
import com.example.zongshe1.modules.risk.datasource.ExternalDataOrchestrator;
import com.example.zongshe1.modules.risk.datasource.ExternalDataSourceAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 组装一次风控所需的全部变量：库内字段 + 外部数据源特征。
 */
@Service
@RequiredArgsConstructor
public class FeatureContextService {

    private final ExternalDataOrchestrator orchestrator;
    private final List<ExternalDataSourceAdapter> adapters;
    private final RiskFeatureQueryService publicFeatures;

    public FeatureSnapshot build(User user, LoanApplication application) {
        FeatureSnapshot snapshot = new FeatureSnapshot();
        snapshot.setBuiltAt(Instant.now());

        // 1. 库内基础变量（与评分卡、基础规则共用）
        int creditScore = user.getCreditScore() != null ? user.getCreditScore() : 0;
        BigDecimal loanAmount = application.getLoanAmount() != null
                ? application.getLoanAmount()
                : BigDecimal.ZERO;

        snapshot.put("credit_score", creditScore);
        snapshot.put("loan_amount", loanAmount);
        snapshot.put("user_status", user.getUserStatus());

        // 2. 外部源并发拉取
        DataSourceQuery query = new DataSourceQuery(
                application.getId(),
                user.getUserId(),
                user.getPhoneNumber(),
                user.getIdCardNumber()
        );

        List<ExternalDataFetchResult> results = orchestrator.fetchAll(query);
        for (ExternalDataFetchResult result : results) {
            snapshot.recordSource(result);
        }

        snapshot.put("required_source_failed", hasRequiredSourceFailure(results));
        var publicSnapshot = publicFeatures.query(user);
        snapshot.put("dishonestHit", publicSnapshot.getDishonestHit());
        snapshot.put("lpr1y", publicSnapshot.getLpr1y());
        snapshot.put("lpr5y", publicSnapshot.getLpr5y());
        snapshot.put("stale", publicSnapshot.getStale());
        snapshot.put("loan_term", application.getLoanTerm());
        return snapshot;
    }

    private boolean hasRequiredSourceFailure(List<ExternalDataFetchResult> results) {
        for (ExternalDataFetchResult result : results) {
            if (result.success()) {
                continue;
            }
            boolean required = adapters.stream()
                    .filter(a -> a.getSourceType() == result.sourceType())
                    .findFirst()
                    .map(ExternalDataSourceAdapter::isRequired)
                    .orElse(false);
            if (required) {
                return true;
            }
        }
        return false;
    }
}
