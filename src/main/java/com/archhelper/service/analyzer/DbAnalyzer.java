package com.archhelper.service.analyzer;

import com.archhelper.model.AnalysisResult.DbQuery;
import com.archhelper.model.AnalysisResult.DbUsage;
import com.archhelper.model.AnalysisResult.StoredProcedure;
import com.archhelper.service.SourceFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds database access across the repo: JPA {@code @Query} annotations, raw SQL
 * string literals, MyBatis mapper statements, plain {@code .sql} scripts, and
 * stored-procedure calls/definitions.
 */
@Component
public class DbAnalyzer {

    private static final int MAX_QUERIES = 200;
    private static final int MAX_PROCS = 100;

    private static final Pattern JPA_QUERY =
            Pattern.compile("@Query\\s*\\((.*?)\\)", Pattern.DOTALL);

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    private static final Pattern SQL_IN_STRING = Pattern.compile(
            "\"((?:\\s*)(?:SELECT|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|MERGE|WITH)\\b[^\"]{0,400})\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PROCEDURE_ANNOTATION =
            Pattern.compile("@Procedure\\s*\\(([^)]*)\\)");

    private static final Pattern CALLABLE =
            Pattern.compile("prepareCall\\s*\\(\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern CALL_STMT =
            Pattern.compile("\\{\\s*call\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXEC_STMT =
            Pattern.compile("\\b(?:EXEC|EXECUTE)\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_PROC =
            Pattern.compile("CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:PROCEDURE|FUNCTION)\\s+([\\w.\"\\[\\]]+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern MYBATIS_STMT =
            Pattern.compile("<(select|insert|update|delete)\\b[^>]*\\bid\\s*=\\s*\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE);

    // JSTL <sql:query>/<sql:update> — SQL either in a sql="..." attribute or in the tag body.
    private static final Pattern JSTL_SQL_ATTR =
            Pattern.compile("<sql:(query|update)\\b[^>]*\\bsql\\s*=\\s*\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern JSTL_SQL_BODY =
            Pattern.compile("<sql:(query|update)\\b[^>]*>(.*?)</sql:\\1>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public DbUsage analyze(List<SourceFile> files) {
        List<DbQuery> queries = new ArrayList<>();
        List<StoredProcedure> procs = new ArrayList<>();
        Set<String> seenQueries = new LinkedHashSet<>();
        Set<String> seenSnippets = new LinkedHashSet<>();
        Set<String> seenProcs = new LinkedHashSet<>();
        int derivedRepoMethods = 0;

        for (SourceFile f : files) {
            if (f.hasExtension("java")) {
                scanJava(f, queries, procs, seenQueries, seenSnippets, seenProcs);
                derivedRepoMethods += countDerivedQueries(f);
            } else if (f.hasExtension("xml")) {
                scanMyBatis(f, queries, procs, seenQueries, seenSnippets, seenProcs);
            } else if (f.hasExtension("sql")) {
                scanSql(f, queries, procs, seenQueries, seenSnippets, seenProcs);
            } else if (f.hasExtension("jsp", "jspx", "jspf", "tag", "tagx")) {
                scanJsp(f, queries, procs, seenQueries, seenSnippets, seenProcs);
            }
        }

        String orm = summarize(queries, procs, derivedRepoMethods);
        return new DbUsage(orm, queries, procs);
    }

    private void scanJava(SourceFile f, List<DbQuery> queries, List<StoredProcedure> procs,
                          Set<String> seenQueries, Set<String> seenSnippets, Set<String> seenProcs) {
        String content = f.content();

        Matcher jq = JPA_QUERY.matcher(content);
        while (jq.find() && queries.size() < MAX_QUERIES) {
            String args = jq.group(1);
            String sql = concatStrings(args);
            if (sql.isBlank()) continue;
            boolean nativeQuery = args.contains("nativeQuery") && args.contains("true");
            addQuery(queries, seenQueries, seenSnippets,
                    nativeQuery ? "JPA @Query (native SQL)" : "JPA @Query (JPQL)",
                    operationOf(sql), loc(f, content, jq.start()), sql);
        }

        Matcher sq = SQL_IN_STRING.matcher(content);
        while (sq.find() && queries.size() < MAX_QUERIES) {
            String sql = sq.group(1).trim();
            // Skip strings already captured as a JPA/MyBatis query.
            if (seenSnippets.contains(normalize(sql))) continue;
            addQuery(queries, seenQueries, seenSnippets, "Inline SQL string", operationOf(sql),
                    loc(f, content, sq.start()), sql);
        }

        Matcher pa = PROCEDURE_ANNOTATION.matcher(content);
        while (pa.find() && procs.size() < MAX_PROCS) {
            String name = firstString(pa.group(1));
            if (name.isBlank()) name = "(see @Procedure)";
            addProc(procs, seenProcs, name, "@Procedure", loc(f, content, pa.start()), pa.group(0));
        }

        Matcher pc = CALLABLE.matcher(content);
        while (pc.find() && procs.size() < MAX_PROCS) {
            String call = pc.group(1);
            Matcher cm = CALL_STMT.matcher(call);
            String name = cm.find() ? cm.group(1) : call;
            addProc(procs, seenProcs, name, "CallableStatement", loc(f, content, pc.start()), call);
        }
    }

    private void scanMyBatis(SourceFile f, List<DbQuery> queries, List<StoredProcedure> procs,
                             Set<String> seenQueries, Set<String> seenSnippets, Set<String> seenProcs) {
        String content = f.content();
        if (!content.contains("<mapper") && !content.toLowerCase().contains("mybatis")) {
            return;
        }
        Matcher m = MYBATIS_STMT.matcher(content);
        while (m.find() && queries.size() < MAX_QUERIES) {
            String tag = m.group(1).toUpperCase();
            String id = m.group(2);
            addQuery(queries, seenQueries, seenSnippets, "MyBatis <" + tag.toLowerCase() + ">",
                    tag.equals("SELECT") ? "SELECT" : tag,
                    loc(f, content, m.start()), id + "  (statement id)");
        }
        Matcher call = CALL_STMT.matcher(content);
        while (call.find() && procs.size() < MAX_PROCS) {
            addProc(procs, seenProcs, call.group(1), "MyBatis CALL", loc(f, content, call.start()), call.group(0));
        }
    }

    private void scanSql(SourceFile f, List<DbQuery> queries, List<StoredProcedure> procs,
                         Set<String> seenQueries, Set<String> seenSnippets, Set<String> seenProcs) {
        String content = f.content();
        Matcher cp = CREATE_PROC.matcher(content);
        while (cp.find() && procs.size() < MAX_PROCS) {
            addProc(procs, seenProcs, cp.group(1), "CREATE PROCEDURE/FUNCTION",
                    loc(f, content, cp.start()), cp.group(0));
        }
        Matcher exec = EXEC_STMT.matcher(content);
        while (exec.find() && procs.size() < MAX_PROCS) {
            addProc(procs, seenProcs, exec.group(1), "EXEC", loc(f, content, exec.start()), exec.group(0));
        }
        // Capture top-level DML statements.
        for (String stmtKeyword : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE"}) {
            Matcher s = Pattern.compile("(?im)^\\s*(" + stmtKeyword + "\\b.{0,300})").matcher(content);
            while (s.find() && queries.size() < MAX_QUERIES) {
                String sql = s.group(1).trim();
                addQuery(queries, seenQueries, seenSnippets, "SQL script", stmtKeyword, loc(f, content, s.start()), sql);
            }
        }
    }

    private void scanJsp(SourceFile f, List<DbQuery> queries, List<StoredProcedure> procs,
                         Set<String> seenQueries, Set<String> seenSnippets, Set<String> seenProcs) {
        String content = f.content();

        // JSTL <sql:query sql="..."> and <sql:update sql="...">.
        Matcher attr = JSTL_SQL_ATTR.matcher(content);
        while (attr.find() && queries.size() < MAX_QUERIES) {
            String sql = attr.group(2).trim();
            addQuery(queries, seenQueries, seenSnippets, "JSTL <sql:" + attr.group(1).toLowerCase() + ">",
                    operationOf(sql), loc(f, content, attr.start()), sql);
        }
        Matcher body = JSTL_SQL_BODY.matcher(content);
        while (body.find() && queries.size() < MAX_QUERIES) {
            String sql = stripTags(body.group(2)).trim();
            if (sql.isBlank()) continue;
            addQuery(queries, seenQueries, seenSnippets, "JSTL <sql:" + body.group(1).toLowerCase() + ">",
                    operationOf(sql), loc(f, content, body.start()), sql);
        }

        // SQL inside scriptlets (<% ... %>) appears as ordinary Java string literals.
        Matcher sq = SQL_IN_STRING.matcher(content);
        while (sq.find() && queries.size() < MAX_QUERIES) {
            String sql = sq.group(1).trim();
            if (seenSnippets.contains(normalize(sql))) continue;
            addQuery(queries, seenQueries, seenSnippets, "JSP scriptlet SQL", operationOf(sql),
                    loc(f, content, sq.start()), sql);
        }

        // Stored-procedure calls inside scriptlets.
        Matcher pc = CALLABLE.matcher(content);
        while (pc.find() && procs.size() < MAX_PROCS) {
            String call = pc.group(1);
            Matcher cm = CALL_STMT.matcher(call);
            String name = cm.find() ? cm.group(1) : call;
            addProc(procs, seenProcs, name, "JSP CallableStatement", loc(f, content, pc.start()), call);
        }
    }

    private String stripTags(String s) {
        return s.replaceAll("<[^>]+>", " ");
    }

    private int countDerivedQueries(SourceFile f) {
        String content = f.content();
        if (!content.contains("Repository")) {
            return 0;
        }
        int count = 0;
        Matcher m = Pattern.compile("\\b(find|read|get|query|count|exists|delete|remove)\\w*By\\w+\\s*\\(")
                .matcher(content);
        while (m.find()) {
            count++;
        }
        return count;
    }

    private void addQuery(List<DbQuery> queries, Set<String> seen, Set<String> seenSnippets,
                          String kind, String op, String location, String snippet) {
        String norm = normalize(snippet);
        String key = kind + "|" + norm;
        if (seen.add(key)) {
            seenSnippets.add(norm);
            queries.add(new DbQuery(kind, op, location, truncate(snippet)));
        }
    }

    private void addProc(List<StoredProcedure> procs, Set<String> seen, String name, String kind,
                         String location, String snippet) {
        String key = kind + "|" + name.toLowerCase();
        if (seen.add(key)) {
            procs.add(new StoredProcedure(name, kind, location, truncate(snippet)));
        }
    }

    private String concatStrings(String args) {
        StringBuilder sb = new StringBuilder();
        Matcher m = STRING_LITERAL.matcher(args);
        while (m.find()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(m.group(1).trim());
        }
        return sb.toString().trim();
    }

    private String firstString(String args) {
        Matcher m = STRING_LITERAL.matcher(args);
        return m.find() ? m.group(1) : "";
    }

    private String operationOf(String sql) {
        String s = sql.trim().toUpperCase();
        if (s.startsWith("SELECT") || s.startsWith("WITH") || s.startsWith("FROM")) return "SELECT";
        if (s.startsWith("INSERT")) return "INSERT";
        if (s.startsWith("UPDATE")) return "UPDATE";
        if (s.startsWith("DELETE")) return "DELETE";
        if (s.startsWith("MERGE")) return "MERGE";
        if (s.startsWith("CALL") || s.contains("CALL ")) return "CALL";
        if (s.startsWith("CREATE") || s.startsWith("ALTER") || s.startsWith("DROP")) return "DDL";
        return "UNKNOWN";
    }

    private String loc(SourceFile f, String content, int index) {
        int line = (int) content.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
        return f.relativePath() + ":" + line;
    }

    private String truncate(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + " ..." : t;
    }

    private String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private String summarize(List<DbQuery> queries, List<StoredProcedure> procs, int derived) {
        if (queries.isEmpty() && procs.isEmpty() && derived == 0) {
            return "No explicit SQL, JPA queries or stored procedures were detected.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Detected ").append(queries.size()).append(" query definition(s) and ")
                .append(procs.size()).append(" stored-procedure reference(s).");
        if (derived > 0) {
            sb.append(" Additionally found ~").append(derived)
                    .append(" Spring Data derived query method(s) (findBy/…) that generate SQL automatically.");
        }
        return sb.toString();
    }
}
