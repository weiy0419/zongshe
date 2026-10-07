package com.example.zongshe1.modules.risk.service.ahp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@Slf4j
public class AhpWeightCalculator {
    // Saaty 随机一致性指标，支持 1~10 维；超出已定义 RI 的维度明确拒绝。
    private static final double[] RI = {0, 0, 0, .58, .90, 1.12, 1.24, 1.32, 1.41, 1.45, 1.49};
    public AhpResult calculate(List<String> models, List<List<Double>> matrix, Map<String, Object> source) {
        int n = models.size();
        if (n < 1 || n >= RI.length || new HashSet<>(models).size() != n
                || models.stream().anyMatch(Objects::isNull) || matrix.size() != n) {
            throw new IllegalArgumentException("Invalid AHP dimensions or model names");
        }
        for (var row : matrix) {
            if (row == null || row.size() != n) throw new IllegalArgumentException("AHP matrix must be square");
            for (Double value : row) {
                if (value == null || !Double.isFinite(value) || value < 1.0 / 9 - 1e-10 || value > 9 + 1e-10)
                    throw new IllegalArgumentException("AHP entries must be within 1/9..9");
            }
        }
        double[] weights = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(matrix.get(i).get(i) - 1) > 1e-9)
                throw new IllegalArgumentException("AHP diagonal must equal 1");
            double logProduct = 0;
            for (int j = 0; j < n; j++) {
                if (Math.abs(matrix.get(i).get(j) * matrix.get(j).get(i) - 1) > 1e-9)
                    throw new IllegalArgumentException("AHP matrix must be reciprocal");
                logProduct += Math.log(matrix.get(i).get(j));
            }
            // 对数形式计算几何平均，与乘积开 n 次方等价，避免乘积溢出。
            weights[i] = Math.exp(logProduct / n);
            sum += weights[i];
        }
        for (int i = 0; i < n; i++) weights[i] /= sum;
        double lambda = 0;
        for (int i = 0; i < n; i++) {
            double aw = 0;
            for (int j = 0; j < n; j++) aw += matrix.get(i).get(j) * weights[j];
            lambda += aw / weights[i] / n;
        }
        double ci = n <= 2 ? 0 : Math.max(0, (lambda - n) / (n - 1));
        double cr = n <= 2 ? 0 : ci / RI[n];
        boolean consistent = cr <= .1;
        boolean missing = "MISSING_LPR".equals(source.get("fallbackReason"));
        boolean fallback = !consistent || missing;
        String explanation = !consistent ? "CR_EXCEEDS_0.1: equal weights; original diagnostics retained"
                : missing ? "MISSING_LPR: equal weights" : "ROW_GEOMETRIC_MEAN";
        if (fallback) {
            Arrays.fill(weights, 1.0 / n);
            log.warn("AHP fallback: {}, CR={}", explanation, cr);
        }
        Map<String, Double> namedWeights = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) namedWeights.put(models.get(i), weights[i]);
        return new AhpResult(List.copyOf(models), matrix.stream().map(List::copyOf).toList(),
                Collections.unmodifiableMap(namedWeights), n <= 2 ? n : lambda, ci, cr, consistent,
                fallback, Collections.unmodifiableMap(new LinkedHashMap<>(source)), explanation);
    }
}
