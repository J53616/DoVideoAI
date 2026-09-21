package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.TraceBaselineMetrics;
import com.example.server.service.AuthService;
import com.example.server.service.TraceBaselineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/trace-metrics")
public class TraceMetricsController {

    private final TraceBaselineService baselineService;
    private final AuthService authService;

    public TraceMetricsController(TraceBaselineService baselineService, AuthService authService) {
        this.baselineService = baselineService;
        this.authService = authService;
    }

    @GetMapping
    public Result<TraceBaselineMetrics> baseline(
            @RequestParam(defaultValue = "7") int days,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        authService.requireAdmin(userId);
        if (days < 1 || days > 90) {
            throw new IllegalArgumentException("days 必须在 1 到 90 之间");
        }
        return Result.ok(baselineService.calculate(days));
    }
}
