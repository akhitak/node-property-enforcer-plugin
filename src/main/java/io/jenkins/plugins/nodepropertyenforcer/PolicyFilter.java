package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.util.PluginServletFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import jenkins.YesNoMaybe;
import jenkins.model.Jenkins;

/** Preflight for stock agent forms. Core retains binding, persistence, authorization and CSRF checks. */
@Extension(dynamicLoadable = YesNoMaybe.NO)
public final class PolicyFilter implements Filter {
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
    public void start() throws ServletException {
        PluginServletFilter.addFilter(this);
    }

    @Terminator
    public void stop() throws ServletException {
        PluginServletFilter.removeFilter(this);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http && response instanceof HttpServletResponse output) {
            String problem = preflight(http);
            if (problem != null) {
                http.setAttribute("nodePropertyPolicy.error", problem);
                http.getRequestDispatcher("/node-property-rejection/").forward(http, output);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String preflight(HttpServletRequest request) {
        List<Requirement> rules = NodePropertyPolicy.get().enabledRequirements();
        if (!applies(request, rules)) {
            return null;
        }
        String path = request.getPathInfo().replaceFirst("^/manage/", "/");
        String[] parts = path.split("/+");
        if (parts.length < 3 || !"computer".equals(parts[1])) {
            return null;
        }
        try {
            return checkRoute(request, parts, rules);
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static boolean applies(HttpServletRequest request, List<Requirement> rules) {
        return "POST".equals(request.getMethod()) && !rules.isEmpty() && request.getPathInfo() != null;
    }

    private static String checkRoute(HttpServletRequest request, String[] parts, List<Requirement> rules)
            throws IOException {
        if (parts.length == 3) {
            return checkCreateRoute(request, parts[2], rules);
        }
        if (parts.length == 4 && java.util.Set.of("configSubmit", "config.xml").contains(parts[3])) {
            return checkUpdate(request, parts, rules);
        }
        return null;
    }

    private static String checkCreateRoute(HttpServletRequest request, String operation, List<Requirement> rules)
            throws IOException {
        if ("doCreateItem".equals(operation)) {
            Jenkins.get().checkPermission(Computer.CREATE);
            return SubmittedProperties.check(request, rules, SubmittedProperties.type(request));
        }
        if ("createItem".equals(operation)) {
            Jenkins.get().checkPermission(Computer.CREATE);
            return checkCreation(request);
        }
        return null;
    }

    private static String checkCreation(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType != null && !contentType.startsWith("application/x-www-form-urlencoded")) {
            return "With required node properties enabled, create agents using the Jenkins web form.";
        }
        if ("copy".equals(SubmittedProperties.parameter(request, "mode"))) {
            return checkCopy(SubmittedProperties.parameter(request, "from"));
        }
        return null;
    }

    private static String checkCopy(String from) {
        Node source = from == null ? null : Jenkins.get().getNode(from);
        if (source == null) {
            return null;
        }
        source.checkPermission(Computer.CONFIGURE);
        return NodePropertyPolicy.get().violation(source);
    }

    private static String checkUpdate(HttpServletRequest request, String[] parts, List<Requirement> rules)
            throws IOException {
        Node node = Jenkins.get().getNode(parts[2]);
        if (node == null) {
            return null;
        }
        node.checkPermission(Computer.CONFIGURE);
        if (!NodePropertyPolicy.get().getBehavior().isEnforceEdits()) {
            return null;
        }
        return "config.xml".equals(parts[3])
                ? "With required node properties enabled, update agents using the Jenkins web form."
                : SubmittedProperties.check(request, rules, node.getClass());
    }
}
