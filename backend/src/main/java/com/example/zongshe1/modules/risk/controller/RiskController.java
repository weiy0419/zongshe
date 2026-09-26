package com.example.zongshe1.modules.risk.controller;

import com.example.zongshe1.common.dto.FeatureSnapshotDTO;
import com.example.zongshe1.modules.risk.entity.RiskReport;
import com.example.zongshe1.common.dto.RiskAssessmentRequest;
import com.example.zongshe1.common.dto.RiskReportDTO;
import com.example.zongshe1.modules.risk.feature.RiskFeatureQueryService;
import com.example.zongshe1.modules.risk.service.RiskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/risk")
@Tag(name = "风控管理", description = "评分卡风控与风险评估接口")
@Slf4j
@RequiredArgsConstructor
public class RiskController {

    private final RiskService riskService;

    private final RiskFeatureQueryService riskFeatureQueryService;

    @PostMapping("/assess")
    @Operation(summary = "风险评估（第四周两阶段）", description = "读取已有申请，执行硬规则准入及 WOE 授信；提供产品 ID 时使用 Redis 去重")
    public ResponseEntity<RiskReportDTO> assess(@RequestBody RiskAssessmentRequest request) {
        log.info("接收风险评估请求：applicationId={}, userId={}, amount={}",
                request.getApplicationId(), request.getUserId(), request.getLoanAmount());

        return ResponseEntity.ok(riskService.assess(request));
    }

    @GetMapping("/features/{userId}")
    @Operation(
            summary = "查询用户风控特征快照",
            description = "根据用户ID查询失信、LPR等外部特征"
    )
    public ResponseEntity<FeatureSnapshotDTO> getFeatures(
            @PathVariable Long userId
    ){

        FeatureSnapshotDTO snapshot =
                riskFeatureQueryService.query(userId);

        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/applications/{applicationId}/assessment")
    @Operation(summary = "获取风控评估详情", description = "查询贷款申请的风控评估结果，无报告时自动执行评估")
    public ResponseEntity<Map<String, Object>> getAssessment(@PathVariable Long applicationId) {
        log.info("查询风控评估：applicationId={}", applicationId);
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> assessment = riskService.getRiskAssessmentDetail(applicationId);
            result.put("success", true);
            result.put("data", assessment);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询风控评估失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/applications/{applicationId}/assess")
    @Operation(summary = "触发风控评估", description = "手动触发贷款申请的风控评估")
    public ResponseEntity<Map<String, Object>> triggerAssessment(@PathVariable Long applicationId) {
        log.info("触发风控评估：applicationId={}", applicationId);
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> assessment = riskService.performRiskAssessment(applicationId);
            result.put("success", true);
            result.put("data", assessment);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("触发风控评估失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/applications/{applicationId}/report")
    @Operation(
            summary = "获取风控报告",
            description = "查询已持久化的新版风控评估结果，不重新执行评估"
    )
    public ResponseEntity<Map<String, Object>> getReport(@PathVariable Long applicationId) {
        log.info("查询风控报告：applicationId={}", applicationId);

        Map<String, Object> result = new HashMap<>();

        try {
            RiskReportDTO report =
                    riskService.getRiskReportDTOByApplicationId(applicationId);

            if (report == null) {
                result.put("success", false);
                result.put("message", "暂无风控报告");
                return ResponseEntity.ok(result);
            }

            result.put("success", true);
            result.put("data", report);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("查询风控报告失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/applications/batch-assess")
    @Operation(summary = "批量风控评估", description = "对多个贷款申请批量执行风控评估")
    public ResponseEntity<Map<String, Object>> batchAssess(@RequestBody Map<String, List<Long>> request) {
        List<Long> applicationIds = request.get("applicationIds");
        if (applicationIds == null || applicationIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "applicationIds 不能为空"
            ));
        }
        Map<String, Object> result = riskService.batchRiskAssessment(applicationIds);
        result.put("success", true);
        return ResponseEntity.ok(result);
    }
}
