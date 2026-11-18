package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Action;
import hudson.model.Computer;
import hudson.model.InvisibleAction;
import hudson.model.Item;
import hudson.model.Node;
import hudson.model.Run;
import hudson.util.HttpResponses;
import java.util.Collection;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.Stapler;

/** Permission-checked, read-only status scoped to the node or build being viewed. */
public final class PolicyStatus extends InvisibleAction {
    private final Computer computer;
    private final Run<?, ?> run;

    private PolicyStatus(Computer computer, Run<?, ?> run) {
        this.computer = computer;
        this.run = run;
    }

    @Override
    public String getUrlName() {
        return "node-property-status";
    }

    @org.kohsuke.stapler.verb.GET
    public HttpResponse doIndex() {
        Jenkins.get().checkPermission(Jenkins.READ);
        if (run != null) {
            run.getParent().checkPermission(Item.READ);
        }
        NodePropertyPolicy policy = NodePropertyPolicy.get();
        PolicyBehavior behavior = policy.getBehavior();
        JSONObject result = new JSONObject();
        result.put("refreshSeconds", behavior.getRefreshSeconds());
        result.put("blockBuilds", behavior.isBlockBuilds());
        result.put("building", run != null && run.isBuilding());
        result.put("buildPage", run != null);
        result.put("problems", problems(policy, behavior));
        return HttpResponses.okJSON(result);
    }

    private JSONArray problems(NodePropertyPolicy policy, PolicyBehavior behavior) {
        JSONArray problems = new JSONArray();
        boolean visible = run == null ? behavior.isShowNodeWarnings() : behavior.isShowBuildWarnings();
        if (visible) {
            for (Node node : nodes()) {
                addProblem(problems, node, policy);
            }
        }
        return problems;
    }

    private List<Node> nodes() {
        if (computer != null) {
            return nodeList(computer.getNode());
        }
        if (run instanceof AbstractBuild<?, ?> build) {
            return nodeList(build.getBuiltOn());
        }
        BuildAgents history = run.getAction(BuildAgents.class);
        return history == null
                ? List.of()
                : history.getNames().stream()
                        .map(Jenkins.get()::getNode)
                        .filter(java.util.Objects::nonNull)
                        .toList();
    }

    private static List<Node> nodeList(Node node) {
        return node == null ? List.of() : List.of(node);
    }

    private static void addProblem(JSONArray problems, Node node, NodePropertyPolicy policy) {
        String message = policy.violation(node);
        if (message == null) {
            return;
        }
        JSONObject problem = new JSONObject();
        problem.put("node", node.getDisplayName());
        problem.put("message", message);
        Computer computer = node.toComputer();
        if (computer != null && node.hasPermission(Computer.CONFIGURE)) {
            problem.put("url", Stapler.getCurrentRequest2().getContextPath() + "/" + computer.getUrl() + "configure");
        }
        problems.add(problem);
    }

    @Extension
    public static final class Computers extends TransientActionFactory<Computer> {
        public Class<Computer> type() {
            return Computer.class;
        }

        @Override
        public Collection<? extends Action> createFor(Computer target) {
            return List.of(new PolicyStatus(target, null));
        }
    }

    @Extension
    public static final class Builds extends TransientActionFactory<Run> {
        @Override
        public Class<Run> type() {
            return Run.class;
        }

        @Override
        public Collection<? extends Action> createFor(Run target) {
            return List.of(new PolicyStatus(null, target));
        }
    }
}
