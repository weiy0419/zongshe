package com.example.zongshe1.modules.risk.service.scoring.impl;

import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.risk.feature.CurrentRiskFeatures;
import com.example.zongshe1.modules.risk.feature.FeatureSnapshot;
import com.example.zongshe1.modules.risk.service.scoring.ScoringCardEngine;
import com.example.zongshe1.modules.risk.service.scoring.ScoringCardOutcome;
import com.example.zongshe1.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * WOE 申请卡（woe-card-v1）：WOE = ln(好样本占比 / 坏样本占比)，卡分越高资质越好。
 * 分布为课程演示的专家假设，不是训练统计，不输出违约概率。
 * 信用分、金额分别映射至 5~50、10~30 分；逾期和在网月数仅保留为旧卡兼容调整。
 * LEGACY_ADJUSTMENT 不是 WOE 计分项，也不代表外部公示模型已实现。
 * 显式配置 risk.scoring-card.engine=woe 才注册，默认不改变旧业务。
 */
@Component
@Primary
@ConditionalOnProperty(name = "risk.scoring-card.engine", havingValue = "woe")
@RequiredArgsConstructor
public class WoeScoringCardEngine implements ScoringCardEngine {

    public static final String VERSION = "woe-card-v1";
    private final CurrentRiskFeatures currentRiskFeatures;

    @Override
    public ScoringCardOutcome evaluate(User user, LoanApplication application) {
        Objects.requireNonNull(user, "user 不能为空");
        Objects.requireNonNull(application, "application 不能为空");
        FeatureSnapshot snapshot = currentRiskFeatures.get();
        int credit = snapshot == null
                ? (user.getCreditScore() == null ? 0 : user.getCreditScore())
                : snapshot.getInt("credit_score", 0);
        BigDecimal amount = snapshot != null && snapshot.get("loan_amount") instanceof BigDecimal value
                ? value : application.getLoanAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("申请金额必须大于 0");
        }

        List<Map<String, Object>> breakdown = new ArrayList<>();
        int creditBin = credit >= 700 ? 3 : credit >= 600 ? 2 : credit >= 500 ? 1 : 0;
        double[] creditGood = {0.10, 0.20, 0.30, 0.40};
        double[] creditBad = {0.40, 0.30, 0.20, 0.10};
        String[] creditLabels = {"(-∞,500)", "[500,600)", "[600,700)", "[700,+∞)"};
        int total = addWoe(breakdown, "credit_score", credit, creditLabels[creditBin],
                creditGood[creditBin], creditBad[creditBin], 27.5, 22.5 / Math.log(4));

        int amountBin = amount.compareTo(new BigDecimal("50000")) <= 0 ? 0
                : amount.compareTo(new BigDecimal("200000")) <= 0 ? 1 : 2;
        double[] amountGood = {0.50, 0.30, 0.20};
        double[] amountBad = {0.20, 0.30, 0.50};
        String[] amountLabels = {"(0,50000]", "(50000,200000]", "(200000,+∞)"};
        total += addWoe(breakdown, "loan_amount", amount, amountLabels[amountBin],
                amountGood[amountBin], amountBad[amountBin], 20, 10 / Math.log(2.5));

        // LEGACY_ADJUSTMENT 仅兼容旧卡的逾期和在网月数加减分，不包含 LPR/失信公示计分。
        // 缺失时不凭空奖励“无逾期”。
        if (snapshot != null && snapshot.get("credit_overdue_count") instanceof Number number) {
            int overdue = number.intValue();
            if (overdue < 0) throw new IllegalArgumentException("逾期次数不能为负数");
            total += addAdjustment(breakdown, "credit_overdue_count", overdue,
                    overdue == 0 ? 10 : overdue <= 2 ? 0 : -20);
        }
        if (snapshot != null && snapshot.get("telecom_online_months") instanceof Number number) {
            int months = number.intValue();
            if (months < 0) throw new IllegalArgumentException("在网月数不能为负数");
            total += addAdjustment(breakdown, "telecom_online_months", months,
                    months >= 24 ? 10 : months >= 12 ? 5 : 0);
        }
        return new ScoringCardOutcome(Math.max(0, Math.min(100, total)), 100, VERSION,
                List.copyOf(breakdown));
    }

    private static int addWoe(List<Map<String, Object>> lines, String code, Object raw,
                              String bin, double good, double bad, double base, double factor) {
        double woe = Math.log(good / bad);
        int points = (int) Math.round(base + factor * woe);
        lines.add(Map.of("featureCode", code, "rawValue", raw, "binLabel", bin,
                "goodDistribution", good, "badDistribution", bad, "woe", woe,
                "basePoints", base, "factor", factor, "points", points,
                "parameterSource", "EXPERT_DEMO"));
        return points;
    }

    private static int addAdjustment(List<Map<String, Object>> lines, String code, int raw, int points) {
        lines.add(Map.of("featureCode", code, "rawValue", raw, "binLabel", String.valueOf(raw),
                "points", points, "method", "LEGACY_ADJUSTMENT"));
        return points;
    }
}
