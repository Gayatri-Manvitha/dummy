package com.archhelper.model;

import java.util.List;

/**
 * Top-level response returned to the UI. Each section is independently rendered:
 * the architecture explanation, the code-flow sequence diagrams, the database
 * usage (queries + stored procedures) and the endpoint outputs.
 */
public record AnalysisResult(
        String repoUrl,
        String repoName,
        String defaultBranch,
        Architecture architecture,
        List<Flow> flows,
        DbUsage dbUsage,
        List<EndpointOutput> outputs,
        List<String> warnings
) {

    public record Architecture(
            String explanation,
            List<String> buildTools,
            List<LanguageStat> languages,
            List<String> frameworks,
            List<String> layers,
            String moduleTree,
            int totalFiles,
            int totalLinesOfCode
    ) {}

    public record LanguageStat(String language, int files, int lines) {}

    /** A single code-flow rendered as a Mermaid sequence diagram. */
    public record Flow(
            String name,
            String description,
            String mermaid
    ) {}

    public record DbUsage(
            String ormSummary,
            List<DbQuery> queries,
            List<StoredProcedure> storedProcedures
    ) {}

    public record DbQuery(
            String kind,      // e.g. JPA @Query, Native SQL, MyBatis, JDBC, Derived
            String operation, // SELECT / INSERT / UPDATE / DELETE / DDL / CALL / UNKNOWN
            String location,  // file:line
            String snippet
    ) {}

    public record StoredProcedure(
            String name,
            String kind,      // CALL / EXEC / @Procedure / CREATE PROCEDURE
            String location,
            String snippet
    ) {}

    public record EndpointOutput(
            String httpMethod,
            String path,
            String handler,
            String returnType,
            String location
    ) {}
}
