package com.archhelper.service;

import com.archhelper.model.AnalysisResult;
import com.archhelper.service.GitRepoService.ClonedRepo;
import com.archhelper.service.analyzer.ArchitectureAnalyzer;
import com.archhelper.service.analyzer.DbAnalyzer;
import com.archhelper.service.analyzer.FlowAnalyzer;
import com.archhelper.service.analyzer.OutputAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full pipeline: clone → scan → run every analyzer → assemble
 * the result → clean up the temporary clone.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final GitRepoService gitRepoService;
    private final FileScanner fileScanner;
    private final ArchitectureAnalyzer architectureAnalyzer;
    private final FlowAnalyzer flowAnalyzer;
    private final DbAnalyzer dbAnalyzer;
    private final OutputAnalyzer outputAnalyzer;

    public AnalysisService(GitRepoService gitRepoService,
                           FileScanner fileScanner,
                           ArchitectureAnalyzer architectureAnalyzer,
                           FlowAnalyzer flowAnalyzer,
                           DbAnalyzer dbAnalyzer,
                           OutputAnalyzer outputAnalyzer) {
        this.gitRepoService = gitRepoService;
        this.fileScanner = fileScanner;
        this.architectureAnalyzer = architectureAnalyzer;
        this.flowAnalyzer = flowAnalyzer;
        this.dbAnalyzer = dbAnalyzer;
        this.outputAnalyzer = outputAnalyzer;
    }

    public AnalysisResult analyze(String repoUrl, String branch) throws IOException {
        return analyze(repoUrl, branch, null, null);
    }

    public AnalysisResult analyze(String repoUrl, String branch, String username, String token) throws IOException {
        ClonedRepo cloned = gitRepoService.clone(repoUrl, branch, username, token);
        Path root = cloned.path();
        List<String> warnings = new ArrayList<>();
        try {
            List<SourceFile> files = fileScanner.scan(root);
            if (files.isEmpty()) {
                warnings.add("No analyzable source files were found in this repository.");
            }
            String repoName = deriveRepoName(repoUrl);

            AnalysisResult.Architecture architecture = architectureAnalyzer.analyze(repoName, files);
            List<AnalysisResult.Flow> flows = flowAnalyzer.analyze(files);
            AnalysisResult.DbUsage dbUsage = dbAnalyzer.analyze(files);
            List<AnalysisResult.EndpointOutput> outputs = outputAnalyzer.analyze(files);

            if (flows.isEmpty()) {
                warnings.add("No call flows were detected. Sequence-diagram generation currently "
                        + "targets Java code (Spring controllers/services and Java Servlets + JSP views).");
            }

            return new AnalysisResult(
                    repoUrl, repoName, cloned.branch(),
                    architecture, flows, dbUsage, outputs, warnings
            );
        } finally {
            gitRepoService.cleanup(root);
            log.info("Cleaned up temporary clone for {}", repoUrl);
        }
    }

    private String deriveRepoName(String repoUrl) {
        String url = repoUrl.trim();
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }
}
