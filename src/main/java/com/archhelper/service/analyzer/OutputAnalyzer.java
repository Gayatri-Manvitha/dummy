package com.archhelper.service.analyzer;

import com.archhelper.model.AnalysisResult.EndpointOutput;
import com.archhelper.service.SourceFile;
import com.archhelper.service.analyzer.JavaParser.ClassInfo;
import com.archhelper.service.analyzer.JavaParser.MethodInfo;
import com.archhelper.service.analyzer.JavaParser.Stereotype;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists what each REST endpoint produces (its HTTP method, path, handler and
 * declared return/output type) so the user can see the API surface.
 */
@Component
public class OutputAnalyzer {

    private static final Pattern FORWARD = Pattern.compile(
            "getRequestDispatcher\\s*\\(\\s*\"([^\"]+)\"|<jsp:forward\\s+page\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern REDIRECT = Pattern.compile("sendRedirect\\s*\\(\\s*\"([^\"]+)\"");

    public List<EndpointOutput> analyze(List<SourceFile> files) {
        JavaParser parser = new JavaParser();
        List<EndpointOutput> outputs = new ArrayList<>();
        for (SourceFile f : files) {
            ClassInfo ci = parser.parse(f);
            if (ci == null
                    || (ci.stereotype != Stereotype.CONTROLLER && ci.stereotype != Stereotype.SERVLET)) {
                continue;
            }
            boolean servlet = ci.stereotype == Stereotype.SERVLET;
            for (MethodInfo m : ci.methods) {
                if (m.httpMethod() == null) {
                    continue;
                }
                outputs.add(new EndpointOutput(
                        m.httpMethod(),
                        joinPath(ci.basePath, m.path()),
                        ci.simpleName + "." + m.name() + "()",
                        outputType(servlet, m),
                        ci.filePath + ":" + m.line()
                ));
            }
        }
        return outputs;
    }

    private String outputType(boolean servlet, MethodInfo m) {
        String view = firstView(m.body());
        if (view != null) {
            return "view → " + view;
        }
        if (servlet) {
            return "HTTP response (writer)";
        }
        return prettyType(m.returnType());
    }

    private String firstView(String body) {
        if (body == null) return null;
        Matcher fw = FORWARD.matcher(body);
        if (fw.find()) {
            return fw.group(1) != null ? fw.group(1) : fw.group(2);
        }
        Matcher rd = REDIRECT.matcher(body);
        if (rd.find()) {
            return "redirect:" + rd.group(1);
        }
        return null;
    }

    private String prettyType(String t) {
        if (t == null || t.isBlank()) return "void";
        return t.trim();
    }

    private String joinPath(String base, String sub) {
        String b = base == null ? "" : base.trim();
        String s = sub == null ? "" : sub.trim();
        if (b.isEmpty()) return s.isEmpty() ? "/" : s;
        if (s.isEmpty()) return b;
        if (b.endsWith("/") && s.startsWith("/")) return b + s.substring(1);
        if (!b.endsWith("/") && !s.startsWith("/")) return b + "/" + s;
        return b + s;
    }
}
