package com.archhelper.controller;

import com.archhelper.model.AnalysisRequest;
import com.archhelper.model.AnalysisResult;
import com.archhelper.service.AnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody AnalysisRequest request) {
        if (request == null || request.getRepoUrl() == null || request.getRepoUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "repoUrl is required"));
        }
        try {
            AnalysisResult result = analysisService.analyze(request.getRepoUrl(), request.getBranch());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Analysis failed for {}", request.getRepoUrl(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Analysis failed: " + e.getMessage()));
        }
    }
}
