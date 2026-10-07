package com.example.zongshe1.modules.risk.service.ahp;

import java.util.*;

/** modelOrder 同时定义矩阵行列和权重的模型对应关系。 */
public record AhpResult(List<String> modelOrder, List<List<Double>> matrix,
                        Map<String, Double> weights, double lambdaMax, double ci, double cr,
                        boolean consistent, boolean fallbackUsed, Map<String, Object> source,
                        String explanation) {
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelOrder", modelOrder);
        result.put("matrix", matrix);
        result.put("weights", weights);
        result.put("lambdaMax", lambdaMax);
        result.put("ci", ci);
        result.put("cr", cr);
        result.put("consistent", consistent);
        result.put("fallbackUsed", fallbackUsed);
        result.put("source", source);
        result.put("explanation", explanation);
        return result;
    }
}
