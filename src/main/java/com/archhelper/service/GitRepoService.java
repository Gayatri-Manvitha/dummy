package com.archhelper.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Clones a remote git repository (shallow, single branch) into a temporary
 * directory so it can be analyzed, and removes it afterwards.
 */
@Service
public class GitRepoService {

    private static final Logger log = LoggerFactory.getLogger(GitRepoService.class);

    @Value("${analysis.clone-timeout-seconds:120}")
    private int cloneTimeoutSeconds;

    public record ClonedRepo(Path path, String branch) {}

    public ClonedRepo clone(String repoUrl, String branch) throws IOException {
        return clone(repoUrl, branch, null, null);
    }

    public ClonedRepo clone(String repoUrl, String branch, String username, String token) throws IOException {
        String normalized = normalizeUrl(repoUrl);
        Path tempDir = Files.createTempDirectory("arch-helper-");
        log.info("Cloning {} into {} (auth: {})", normalized, tempDir,
                (token != null && !token.isBlank()) ? "yes" : "no");

        var cloneCmd = Git.cloneRepository()
                .setURI(normalized)
                .setDirectory(tempDir.toFile())
                .setDepth(1)
                .setCloneSubmodules(false)
                .setTimeout(cloneTimeoutSeconds);

        // Private repos over HTTPS: authenticate with a personal access token.
        if (token != null && !token.isBlank()) {
            // For GitHub/GitLab PATs the token is the password; a username is often
            // optional, so default it to the token when not supplied.
            String user = (username != null && !username.isBlank()) ? username.trim() : token.trim();
            cloneCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, token.trim()));
        }

        if (branch != null && !branch.isBlank()) {
            cloneCmd.setBranch(branch.trim());
        }

        try (Git git = cloneCmd.call()) {
            String resolvedBranch = resolveBranch(git.getRepository(), branch);
            return new ClonedRepo(tempDir, resolvedBranch);
        } catch (Exception e) {
            cleanup(tempDir);
            throw new IOException("Failed to clone repository: " + e.getMessage(), e);
        }
    }

    private String resolveBranch(Repository repository, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        try {
            String full = repository.getFullBranch();
            if (full != null && full.startsWith("refs/heads/")) {
                return full.substring("refs/heads/".length());
            }
            return full != null ? full : "(default)";
        } catch (IOException e) {
            return "(default)";
        }
    }

    private String normalizeUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL is required");
        }
        String url = repoUrl.trim();
        // Local checkout: a file:// URL or an existing local directory/path.
        if (url.startsWith("file://")) {
            return url;
        }
        java.io.File local = new java.io.File(url);
        if (local.exists() && local.isDirectory()) {
            return local.toURI().toString();
        }
        // Accept "owner/repo" shorthand for GitHub.
        if (url.matches("[\\w.-]+/[\\w.-]+")) {
            url = "https://github.com/" + url;
        }
        if (!url.endsWith(".git") && url.startsWith("http")) {
            url = url + ".git";
        }
        if (!(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("git@"))) {
            throw new IllegalArgumentException("Only http(s)/ssh git URLs or a local repo path are supported");
        }
        return url;
    }

    public void cleanup(Path path) {
        if (path == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            log.warn("Could not fully clean temp dir {}: {}", path, e.getMessage());
        }
    }
}
