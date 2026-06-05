package com.archhelper.service.analyzer;

import com.archhelper.service.SourceFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately lightweight, heuristic Java parser. It does not build a full
 * AST; instead it extracts just enough structure (stereotype, injected fields,
 * methods + their bodies, and HTTP mappings) for the flow and DB analyzers to
 * trace calls between Spring components.
 */
public class JavaParser {

    public enum Stereotype { CONTROLLER, SERVLET, SERVICE, REPOSITORY, COMPONENT, CONFIG, ENTITY, OTHER }

    public record MethodInfo(
            String name,
            String returnType,
            String httpMethod, // GET/POST/... or null
            String path,       // mapping path or null
            String body,
            int line
    ) {}

    public static final class ClassInfo {
        public String simpleName;
        public String kind; // class | interface
        public Stereotype stereotype = Stereotype.OTHER;
        public String basePath = "";
        public String filePath;
        public String supers = "";
        public final Map<String, String> fields = new LinkedHashMap<>(); // fieldName -> simpleType
        public final List<MethodInfo> methods = new ArrayList<>();
    }

    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(class|interface)\\s+(\\w+)([^\\{]*)\\{");

    private static final Pattern FIELD =
            Pattern.compile("(?m)^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:private|protected|public)\\s+(?:static\\s+)?(?:final\\s+)?([A-Z][\\w]*)(?:<[^>]*>)?\\s+(\\w+)\\s*[;=]");

    private static final Pattern METHOD =
            Pattern.compile("(?m)^[ \\t]*(?:public|protected|private)\\s+(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?([\\w<>\\[\\],.?\\s]+?)\\s+(\\w+)\\s*\\(([^;{]*?)\\)\\s*(?:throws[\\w,\\s.]+)?\\{");

    private static final Pattern MAPPING =
            Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\\(([^)]*)\\))?");

    private static final Pattern PATH_VALUE =
            Pattern.compile("(?:value\\s*=\\s*)?\"([^\"]*)\"");

    public ClassInfo parse(SourceFile file) {
        if (!file.hasExtension("java")) {
            return null;
        }
        String content = file.content();
        Matcher td = TYPE_DECL.matcher(content);
        if (!td.find()) {
            return null;
        }
        ClassInfo info = new ClassInfo();
        info.kind = td.group(1);
        info.simpleName = td.group(2);
        info.supers = td.group(3) == null ? "" : td.group(3);
        info.filePath = file.relativePath();

        String header = content.substring(0, td.start());
        info.stereotype = detectStereotype(header, info.supers, content);
        info.basePath = detectBasePath(header);

        parseFields(content, info);
        parseMethods(content, info);
        return info;
    }

    private Stereotype detectStereotype(String header, String supers, String content) {
        if (header.contains("@RestController") || header.contains("@Controller")) return Stereotype.CONTROLLER;
        if (header.contains("@WebServlet") || supers.contains("HttpServlet")) return Stereotype.SERVLET;
        if (header.contains("@Service")) return Stereotype.SERVICE;
        if (header.contains("@Repository")) return Stereotype.REPOSITORY;
        if (supers.contains("JpaRepository") || supers.contains("CrudRepository")
                || supers.contains("PagingAndSortingRepository") || supers.contains("MongoRepository")) {
            return Stereotype.REPOSITORY;
        }
        if (header.contains("@Configuration")) return Stereotype.CONFIG;
        if (header.contains("@Entity") || header.contains("@Table") || header.contains("@Document")) return Stereotype.ENTITY;
        if (header.contains("@Component")) return Stereotype.COMPONENT;
        // Name-based fallback for legacy DAOs (no annotations).
        return Stereotype.OTHER;
    }

    private String detectBasePath(String header) {
        Matcher m = Pattern.compile("@RequestMapping(?:\\(([^)]*)\\))?").matcher(header);
        if (m.find() && m.group(1) != null) {
            Matcher pv = PATH_VALUE.matcher(m.group(1));
            if (pv.find()) {
                return pv.group(1);
            }
        }
        // Legacy servlets: @WebServlet("/path") or urlPatterns = {"/path"}.
        Matcher ws = Pattern.compile("@WebServlet(?:\\(([^)]*)\\))?").matcher(header);
        if (ws.find() && ws.group(1) != null) {
            String args = ws.group(1);
            Matcher up = Pattern.compile("urlPatterns\\s*=\\s*\\{?\\s*\"([^\"]*)\"").matcher(args);
            if (up.find()) {
                return up.group(1);
            }
            Matcher pv = PATH_VALUE.matcher(args);
            if (pv.find()) {
                return pv.group(1);
            }
        }
        return "";
    }

    private void parseFields(String content, ClassInfo info) {
        Matcher m = FIELD.matcher(content);
        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);
            // Skip obvious constants / loggers.
            if (type.equals("String") || type.equals("Logger") || type.equals("Long")
                    || type.equals("Integer") || type.equals("Boolean") || type.equals("Object")) {
                continue;
            }
            info.fields.putIfAbsent(name, type);
        }
        parseConstructorParams(content, info);
    }

    private void parseConstructorParams(String content, ClassInfo info) {
        Matcher ctor = Pattern.compile(Pattern.quote(info.simpleName) + "\\s*\\(([^)]*)\\)\\s*\\{").matcher(content);
        if (ctor.find()) {
            String params = ctor.group(1);
            Matcher p = Pattern.compile("([A-Z][\\w]*)(?:<[^>]*>)?\\s+(\\w+)").matcher(params);
            while (p.find()) {
                info.fields.putIfAbsent(p.group(2), p.group(1));
            }
        }
    }

    private void parseMethods(String content, ClassInfo info) {
        Matcher m = METHOD.matcher(content);
        while (m.find()) {
            String returnType = m.group(1).trim();
            String name = m.group(2);
            if (name.equals(info.simpleName) || returnType.isEmpty()) {
                continue; // constructor
            }
            int braceIndex = m.end() - 1;
            String body = extractBlock(content, braceIndex);
            int line = (int) content.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;

            String preceding = content.substring(Math.max(0, m.start() - 250), m.start());
            String httpMethod = null;
            String path = null;
            Matcher mp = MAPPING.matcher(preceding);
            String last = null;
            String lastArgs = null;
            while (mp.find()) { // take the closest mapping above the method
                last = mp.group(1);
                lastArgs = mp.group(2);
            }
            if (last != null) {
                httpMethod = last.equals("Request") ? "ANY" : last.toUpperCase();
                path = "";
                if (lastArgs != null) {
                    Matcher pv = PATH_VALUE.matcher(lastArgs);
                    if (pv.find()) {
                        path = pv.group(1);
                    }
                }
            }
            // Legacy servlet entry points map to HTTP verbs by method name.
            if (httpMethod == null) {
                switch (name) {
                    case "doGet" -> httpMethod = "GET";
                    case "doPost" -> httpMethod = "POST";
                    case "doPut" -> httpMethod = "PUT";
                    case "doDelete" -> httpMethod = "DELETE";
                    case "doHead" -> httpMethod = "HEAD";
                    case "service" -> { if (info.stereotype == Stereotype.SERVLET) httpMethod = "ANY"; }
                    default -> { /* not an entry point */ }
                }
            }
            info.methods.add(new MethodInfo(name, returnType, httpMethod, path, body, line));
        }
    }

    private static final Pattern LOCAL_VAR =
            Pattern.compile("\\b([A-Z][\\w]*)(?:<[^>]*>)?\\s+(\\w+)\\s*=");

    /**
     * Extracts local variable declarations from a method body (e.g.
     * {@code UserDao dao = new UserDao();}) so the flow analyzer can resolve
     * receivers in legacy servlet/DAO code that does not use field injection.
     */
    public static Map<String, String> localVars(String body) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (body == null) {
            return vars;
        }
        Matcher m = LOCAL_VAR.matcher(body);
        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);
            if (type.equals("String") || type.equals("Integer") || type.equals("Long")
                    || type.equals("Boolean") || type.equals("Object") || type.equals("List")
                    || type.equals("Map") || type.equals("Set")) {
                continue;
            }
            vars.putIfAbsent(name, type);
        }
        return vars;
    }

    /** Returns the substring inside the braces starting at openBraceIndex (inclusive of contents, excluding braces). */
    private String extractBlock(String content, int openBraceIndex) {
        if (openBraceIndex < 0 || openBraceIndex >= content.length() || content.charAt(openBraceIndex) != '{') {
            return "";
        }
        int depth = 0;
        for (int i = openBraceIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(openBraceIndex + 1, i);
                }
            }
        }
        return content.substring(openBraceIndex + 1);
    }
}
