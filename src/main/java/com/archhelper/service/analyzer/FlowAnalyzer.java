package com.archhelper.service.analyzer;

import com.archhelper.model.AnalysisResult.Flow;
import com.archhelper.service.SourceFile;
import com.archhelper.service.analyzer.JavaParser.ClassInfo;
import com.archhelper.service.analyzer.JavaParser.MethodInfo;
import com.archhelper.service.analyzer.JavaParser.Stereotype;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces code-flow sequence diagrams (Mermaid syntax). For each REST endpoint
 * it traces calls from the controller through services and into repositories,
 * rendering the typical request → response journey.
 */
@Component
public class FlowAnalyzer {

    private static final int MAX_FLOWS = 20;
    private static final int MAX_DEPTH = 6;

    private static final Pattern CALL = Pattern.compile("(\\w+)\\.(\\w+)\\s*\\(");

    // JSP view targets reached from servlets/controllers.
    private static final Pattern FORWARD = Pattern.compile(
            "getRequestDispatcher\\s*\\(\\s*\"([^\"]+)\"|<jsp:forward\\s+page\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern REDIRECT = Pattern.compile("sendRedirect\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern RETURN_VIEW = Pattern.compile("return\\s+\"([^\"]+)\"");

    public List<Flow> analyze(List<SourceFile> files) {
        JavaParser parser = new JavaParser();
        Map<String, ClassInfo> index = new LinkedHashMap<>();
        for (SourceFile f : files) {
            ClassInfo ci = parser.parse(f);
            if (ci != null) {
                index.put(ci.simpleName, ci);
            }
        }

        List<Flow> flows = new ArrayList<>();
        // Primary: one diagram per controller/servlet endpoint.
        for (ClassInfo ci : index.values()) {
            if (ci.stereotype != Stereotype.CONTROLLER && ci.stereotype != Stereotype.SERVLET) {
                continue;
            }
            for (MethodInfo method : ci.methods) {
                if (method.httpMethod() == null) {
                    continue;
                }
                flows.add(buildEndpointFlow(ci, method, index));
                if (flows.size() >= MAX_FLOWS) {
                    return flows;
                }
            }
        }

        // Fallback: if no controllers, diagram public service methods.
        if (flows.isEmpty()) {
            for (ClassInfo ci : index.values()) {
                if (ci.stereotype != Stereotype.SERVICE) {
                    continue;
                }
                for (MethodInfo method : ci.methods) {
                    Flow f = buildServiceFlow(ci, method, index);
                    if (f != null) {
                        flows.add(f);
                    }
                    if (flows.size() >= MAX_FLOWS) {
                        return flows;
                    }
                }
            }
        }
        return flows;
    }

    private Flow buildEndpointFlow(ClassInfo controller, MethodInfo method, Map<String, ClassInfo> index) {
        boolean servlet = controller.stereotype == Stereotype.SERVLET;
        String fullPath = joinPath(controller.basePath, method.path());
        String title = method.httpMethod() + " " + (fullPath.isEmpty() ? "/" : fullPath);

        StringBuilder mermaid = new StringBuilder("sequenceDiagram\n");
        mermaid.append("    autonumber\n");
        mermaid.append("    actor Client\n");
        mermaid.append("    participant ").append(controller.simpleName).append("\n");

        List<String> steps = new ArrayList<>();
        steps.add("    Client->>" + controller.simpleName + ": " + escape(title));
        boolean[] touchedDb = {false};
        traceCalls(controller, method.body(), controller.simpleName, index,
                steps, new HashSet<>(), 0, touchedDb);

        List<String> views = findViews(method.body());
        if (!views.isEmpty()) {
            for (String view : views) {
                steps.add("    " + controller.simpleName + "->>JSPView: " + escape("forward → " + view));
            }
            steps.add("    JSPView-->>Client: rendered HTML");
        } else {
            steps.add("    " + controller.simpleName + "-->>Client: " + escape(shortType(method.returnType())));
        }

        mermaid.append(String.join("\n", steps)).append("\n");

        String kind = servlet ? "Servlet" : "Endpoint";
        String desc = kind + " handled by " + controller.simpleName + "." + method.name()
                + "() — " + controller.filePath + ":" + method.line();
        if (!views.isEmpty()) {
            desc += "  ·  renders: " + String.join(", ", views);
        }
        return new Flow(title + "  (" + controller.simpleName + "." + method.name() + ")", desc, mermaid.toString());
    }

    /** Detects JSP views reached via RequestDispatcher.forward, sendRedirect or MVC view-name returns. */
    private List<String> findViews(String body) {
        List<String> views = new ArrayList<>();
        if (body == null) {
            return views;
        }
        Matcher fw = FORWARD.matcher(body);
        while (fw.find()) {
            String v = fw.group(1) != null ? fw.group(1) : fw.group(2);
            if (v != null && !views.contains(v)) views.add(v);
        }
        Matcher rd = REDIRECT.matcher(body);
        while (rd.find()) {
            String v = rd.group(1);
            if (v != null && !views.contains(v)) views.add(v);
        }
        boolean mvcRender = body.contains("addAttribute") || body.contains("ModelAndView")
                || body.contains("ModelMap") || body.contains("setAttribute");
        if (mvcRender) {
            Matcher rv = RETURN_VIEW.matcher(body);
            while (rv.find()) {
                String v = rv.group(1);
                if (v != null && !v.contains(" ") && !v.startsWith("redirect:")
                        && !views.contains(v) && v.length() < 60) {
                    views.add(v);
                }
            }
        }
        return views.size() > 3 ? views.subList(0, 3) : views;
    }

    private Flow buildServiceFlow(ClassInfo service, MethodInfo method, Map<String, ClassInfo> index) {
        StringBuilder mermaid = new StringBuilder("sequenceDiagram\n");
        mermaid.append("    autonumber\n");
        mermaid.append("    actor Caller\n");
        mermaid.append("    participant ").append(service.simpleName).append("\n");
        List<String> steps = new ArrayList<>();
        steps.add("    Caller->>" + service.simpleName + ": " + escape(method.name() + "()"));
        boolean[] touchedDb = {false};
        traceCalls(service, method.body(), service.simpleName, index, steps, new HashSet<>(), 0, touchedDb);
        if (steps.size() <= 1) {
            return null; // nothing interesting to show
        }
        steps.add("    " + service.simpleName + "-->>Caller: " + escape(shortType(method.returnType())));
        mermaid.append(String.join("\n", steps)).append("\n");
        String desc = service.simpleName + "." + method.name() + "() — " + service.filePath + ":" + method.line();
        return new Flow(service.simpleName + "." + method.name() + "()", desc, mermaid.toString());
    }

    private void traceCalls(ClassInfo owner, String body, String fromParticipant,
                            Map<String, ClassInfo> index, List<String> steps,
                            Set<String> visited, int depth, boolean[] touchedDb) {
        if (body == null || body.isEmpty() || depth > MAX_DEPTH) {
            return;
        }
        // Legacy code resolves dependencies via local vars (new XxxDao()) as well as fields.
        Map<String, String> locals = JavaParser.localVars(body);
        Matcher m = CALL.matcher(body);
        String lastEdge = null;
        while (m.find()) {
            String receiver = m.group(1);
            String calledMethod = m.group(2);
            String type = owner.fields.get(receiver);
            if (type == null) {
                type = locals.get(receiver);
            }
            if (type == null) {
                continue; // not a known dependency (static / this / framework object)
            }
            ClassInfo target = index.get(type);
            String edgeKey = fromParticipant + ">" + type + "#" + calledMethod;
            if (edgeKey.equals(lastEdge)) {
                continue; // collapse immediate duplicates
            }
            lastEdge = edgeKey;

            boolean daoLike = (target != null && target.stereotype == Stereotype.REPOSITORY)
                    || isDaoLikeName(type);

            if (daoLike) {
                steps.add("    " + fromParticipant + "->>" + type + ": " + escape(calledMethod + "()"));
                String op = dbOperation(calledMethod);
                steps.add("    " + type + "->>Database: " + escape(op));
                steps.add("    Database-->>" + type + ": result set");
                steps.add("    " + type + "-->>" + fromParticipant + ": entity/rows");
                touchedDb[0] = true;
            } else if (target != null) {
                steps.add("    " + fromParticipant + "->>" + type + ": " + escape(calledMethod + "()"));
                String recurseKey = type + "#" + calledMethod;
                if (visited.add(recurseKey)) {
                    MethodInfo tm = findMethod(target, calledMethod);
                    if (tm != null) {
                        traceCalls(target, tm.body(), type, index, steps, visited, depth + 1, touchedDb);
                    }
                }
                steps.add("    " + type + "-->>" + fromParticipant + ": return");
            } else {
                // Dependency whose source we did not parse (e.g. external client).
                steps.add("    " + fromParticipant + "->>" + type + ": " + escape(calledMethod + "()"));
                steps.add("    " + type + "-->>" + fromParticipant + ": return");
            }
        }
    }

    private boolean isDaoLikeName(String type) {
        if (type == null) return false;
        String t = type.toLowerCase();
        return t.endsWith("dao") || t.endsWith("repository") || t.endsWith("mapper")
                || t.endsWith("repositoryimpl") || t.endsWith("daoimpl");
    }

    private MethodInfo findMethod(ClassInfo ci, String name) {
        for (MethodInfo mi : ci.methods) {
            if (mi.name().equals(name)) {
                return mi;
            }
        }
        return null;
    }

    private String dbOperation(String method) {
        String n = method.toLowerCase();
        if (n.startsWith("find") || n.startsWith("get") || n.startsWith("read")
                || n.startsWith("list") || n.startsWith("query") || n.startsWith("count")
                || n.startsWith("exists") || n.startsWith("search")) return "SELECT";
        if (n.startsWith("save") || n.startsWith("insert") || n.startsWith("create")
                || n.startsWith("add") || n.startsWith("persist")) return "INSERT/UPDATE";
        if (n.startsWith("update") || n.startsWith("modify") || n.startsWith("set")) return "UPDATE";
        if (n.startsWith("delete") || n.startsWith("remove")) return "DELETE";
        if (n.startsWith("call") || n.startsWith("execute") || n.startsWith("proc")) return "CALL procedure";
        return "query";
    }

    private String joinPath(String base, String sub) {
        String b = base == null ? "" : base.trim();
        String s = sub == null ? "" : sub.trim();
        if (b.isEmpty()) return s;
        if (s.isEmpty()) return b;
        if (b.endsWith("/") && s.startsWith("/")) return b + s.substring(1);
        if (!b.endsWith("/") && !s.startsWith("/")) return b + "/" + s;
        return b + s;
    }

    private String shortType(String t) {
        if (t == null || t.isBlank()) return "void";
        String s = t.trim();
        int lt = s.indexOf('<');
        return lt > 0 ? s.substring(0, lt) : s;
    }

    private String escape(String s) {
        if (s == null) return "";
        // Mermaid message text: avoid characters that break parsing.
        return s.replace(":", " -").replace(";", ",").replace("\n", " ").trim();
    }
}
