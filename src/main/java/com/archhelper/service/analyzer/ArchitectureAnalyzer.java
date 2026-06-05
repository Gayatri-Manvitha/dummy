package com.archhelper.service.analyzer;

import com.archhelper.model.AnalysisResult.Architecture;
import com.archhelper.model.AnalysisResult.LanguageStat;
import com.archhelper.service.SourceFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Derives a high-level picture of the repository: build tooling, languages,
 * frameworks, the layers it is organized into, and a top-level module tree.
 */
@Component
public class ArchitectureAnalyzer {

    private static final Map<String, String> EXT_TO_LANG = Map.ofEntries(
            Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("kts", "Kotlin"),
            Map.entry("scala", "Scala"), Map.entry("groovy", "Groovy"),
            Map.entry("js", "JavaScript"), Map.entry("jsx", "JavaScript"),
            Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"),
            Map.entry("py", "Python"), Map.entry("rb", "Ruby"), Map.entry("go", "Go"),
            Map.entry("cs", "C#"), Map.entry("php", "PHP"), Map.entry("sql", "SQL"),
            Map.entry("html", "HTML"), Map.entry("css", "CSS"),
            Map.entry("jsp", "JSP"), Map.entry("jspx", "JSP"),
            Map.entry("jspf", "JSP"), Map.entry("tag", "JSP"), Map.entry("tagx", "JSP")
    );

    public Architecture analyze(String repoName, List<SourceFile> files) {
        List<String> buildTools = detectBuildTools(files);
        List<LanguageStat> languages = languageStats(files);
        Set<String> frameworks = detectFrameworks(files);
        List<String> layers = detectLayers(files);
        String moduleTree = buildModuleTree(files);
        int totalLines = files.stream().mapToInt(f -> f.lines().size()).sum();

        String explanation = buildExplanation(repoName, buildTools, languages,
                frameworks, layers, files.size());

        return new Architecture(
                explanation,
                buildTools,
                languages,
                new ArrayList<>(frameworks),
                layers,
                moduleTree,
                files.size(),
                totalLines
        );
    }

    private List<String> detectBuildTools(List<SourceFile> files) {
        Set<String> tools = new LinkedHashSet<>();
        for (SourceFile f : files) {
            String n = f.fileName().toLowerCase();
            switch (n) {
                case "pom.xml" -> tools.add("Maven");
                case "build.gradle", "build.gradle.kts", "settings.gradle" -> tools.add("Gradle");
                case "package.json" -> tools.add("npm / Node.js");
                case "requirements.txt", "pyproject.toml", "setup.py" -> tools.add("Python (pip)");
                case "go.mod" -> tools.add("Go modules");
                case "gemfile" -> tools.add("Ruby (Bundler)");
                case "composer.json" -> tools.add("PHP (Composer)");
                case "dockerfile" -> tools.add("Docker");
                case "makefile" -> tools.add("Make");
                default -> { /* ignore */ }
            }
        }
        return new ArrayList<>(tools);
    }

    private List<LanguageStat> languageStats(List<SourceFile> files) {
        Map<String, int[]> counts = new LinkedHashMap<>(); // lang -> [files, lines]
        for (SourceFile f : files) {
            String lang = EXT_TO_LANG.get(f.extension());
            if (lang == null) {
                continue;
            }
            counts.computeIfAbsent(lang, k -> new int[2]);
            counts.get(lang)[0]++;
            counts.get(lang)[1] += f.lines().size();
        }
        List<LanguageStat> stats = new ArrayList<>();
        counts.forEach((lang, c) -> stats.add(new LanguageStat(lang, c[0], c[1])));
        stats.sort(Comparator.comparingInt(LanguageStat::lines).reversed());
        return stats;
    }

    private Set<String> detectFrameworks(List<SourceFile> files) {
        Set<String> frameworks = new TreeSet<>();
        for (SourceFile f : files) {
            String n = f.fileName().toLowerCase();
            String c = f.content();
            if (n.equals("pom.xml") || n.startsWith("build.gradle")) {
                if (c.contains("spring-boot")) frameworks.add("Spring Boot");
                if (c.contains("spring-web") || c.contains("spring-webmvc")) frameworks.add("Spring MVC");
                if (c.contains("spring-data-jpa") || c.contains("hibernate")) frameworks.add("Spring Data JPA / Hibernate");
                if (c.contains("mybatis")) frameworks.add("MyBatis");
                if (c.contains("spring-boot-starter-webflux")) frameworks.add("Spring WebFlux");
                if (c.contains("quarkus")) frameworks.add("Quarkus");
                if (c.contains("micronaut")) frameworks.add("Micronaut");
            }
            if (n.equals("package.json")) {
                if (c.contains("\"react\"")) frameworks.add("React");
                if (c.contains("\"next\"")) frameworks.add("Next.js");
                if (c.contains("@angular/core")) frameworks.add("Angular");
                if (c.contains("\"vue\"")) frameworks.add("Vue");
                if (c.contains("\"express\"")) frameworks.add("Express");
                if (c.contains("\"@nestjs/core\"")) frameworks.add("NestJS");
            }
            if (n.equals("requirements.txt") || n.equals("pyproject.toml")) {
                if (c.toLowerCase().contains("django")) frameworks.add("Django");
                if (c.toLowerCase().contains("flask")) frameworks.add("Flask");
                if (c.toLowerCase().contains("fastapi")) frameworks.add("FastAPI");
            }
            // Source-level hints.
            if (f.hasExtension("java")) {
                if (c.contains("@RestController") || c.contains("@Controller")) frameworks.add("Spring MVC");
                if (c.contains("@Entity") || c.contains("JpaRepository")) frameworks.add("Spring Data JPA / Hibernate");
                if (c.contains("HttpServlet") || c.contains("@WebServlet")) frameworks.add("Java Servlet");
                if (c.contains("org.apache.struts") || c.contains("extends ActionSupport")
                        || c.contains("extends Action")) frameworks.add("Struts");
            }
            if (f.hasExtension("jsp", "jspx", "jspf", "tag", "tagx")) {
                frameworks.add("JSP / JSTL views");
                if (c.contains("http://java.sun.com/jsp/jstl/sql")
                        || c.contains("jakarta.tags.sql")) frameworks.add("JSTL SQL tags");
            }
            if (n.equals("web.xml") && (c.contains("<servlet") || c.contains("javax.servlet")
                    || c.contains("jakarta.servlet"))) {
                frameworks.add("Java Servlet");
            }
        }
        return frameworks;
    }

    private List<String> detectLayers(List<SourceFile> files) {
        Set<String> layers = new LinkedHashSet<>();
        Map<String, String> markers = new LinkedHashMap<>();
        markers.put("controller", "Controllers / API layer");
        markers.put("resource", "Controllers / API layer");
        markers.put("rest", "Controllers / API layer");
        markers.put("servlet", "Servlets / web layer");
        markers.put("action", "Actions (Struts) / web layer");
        markers.put("service", "Service / business layer");
        markers.put("repository", "Repository / data-access layer");
        markers.put("dao", "Repository / data-access layer");
        markers.put("mapper", "Repository / data-access layer");
        markers.put("entity", "Domain / entity model");
        markers.put("domain", "Domain / entity model");
        markers.put("model", "Domain / entity model");
        markers.put("dto", "DTO / transport objects");
        markers.put("config", "Configuration");
        markers.put("security", "Security");
        markers.put("exception", "Error handling");
        markers.put("util", "Utilities");

        boolean hasJsp = false;
        for (SourceFile f : files) {
            String[] segments = f.relativePath().toLowerCase().split("/");
            String fileBase = stripExtension(f.fileName().toLowerCase());
            markers.forEach((marker, label) -> {
                boolean segmentMatch = false;
                // "resource" is matched on file name only ("resources" dir is Maven's, not a JAX-RS layer).
                if (!marker.equals("resource")) {
                    for (String seg : segments) {
                        if (seg.equals(marker) || seg.equals(marker + "s")) {
                            segmentMatch = true;
                            break;
                        }
                    }
                }
                if (segmentMatch || fileBase.contains(marker)) {
                    layers.add(label);
                }
            });
            if (f.hasExtension("jsp", "jspx", "jspf", "tag", "tagx")) {
                hasJsp = true;
            }
        }
        if (hasJsp) {
            layers.add("Views (JSP)");
        }
        return new ArrayList<>(layers);
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String buildModuleTree(List<SourceFile> files) {
        // Aggregate file counts per top two path segments.
        Map<String, Integer> tree = new TreeMap<>();
        for (SourceFile f : files) {
            String[] parts = f.relativePath().split("/");
            String key;
            if (parts.length == 1) {
                key = "(root)";
            } else if (parts.length == 2) {
                key = parts[0];
            } else {
                key = parts[0] + "/" + parts[1];
            }
            tree.merge(key, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        tree.forEach((k, v) -> sb.append(k).append("  (").append(v).append(" files)\n"));
        return sb.toString().trim();
    }

    private String buildExplanation(String repoName, List<String> buildTools,
                                    List<LanguageStat> languages, Set<String> frameworks,
                                    List<String> layers, int fileCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(repoName).append("** is a ");
        String primaryLang = languages.isEmpty() ? "multi-language" : languages.get(0).language();
        sb.append(primaryLang).append(" project");
        if (!frameworks.isEmpty()) {
            sb.append(" built with ").append(String.join(", ", frameworks));
        }
        sb.append(". It contains ").append(fileCount).append(" analyzable source files");
        if (!buildTools.isEmpty()) {
            sb.append(" and is built using ").append(String.join(", ", buildTools));
        }
        sb.append(".\n\n");

        if (!layers.isEmpty()) {
            sb.append("The codebase is organized into the following layers:\n");
            for (String l : layers) {
                sb.append("- ").append(l).append("\n");
            }
            sb.append("\n");
            if (layers.stream().anyMatch(l -> l.contains("Controllers"))
                    && layers.stream().anyMatch(l -> l.contains("Service"))
                    && layers.stream().anyMatch(l -> l.contains("Repository"))) {
                sb.append("This follows a classic layered (Controller → Service → Repository) architecture, ")
                        .append("where HTTP requests enter through controllers, business rules live in services, ")
                        .append("and persistence is delegated to repositories/DAOs.\n");
            }
        } else {
            sb.append("No conventional layer structure (controller/service/repository) was detected; ")
                    .append("the project may use a different organization.\n");
        }
        return sb.toString().trim();
    }
}
