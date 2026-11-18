package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Node;
import hudson.model.PageDecorator;
import hudson.model.Queue;
import hudson.util.HttpResponses;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;

/** Read-only explanations for classic queue widgets; never changes scheduling or saved nodes. */
@Extension
public final class QueueMessages extends PageDecorator {
    public boolean isEnabled() {
        return NodePropertyPolicy.get().getBehavior().isShowBuildWarnings();
    }

    public HttpResponse doStatus(@QueryParameter String ids) {
        Jenkins.get().checkPermission(Jenkins.READ);
        String[] requested = requestedIds(ids);
        var policy = NodePropertyPolicy.get();
        var items = new JSONObject();
        if (policy.getBehavior().isShowBuildWarnings()) {
            for (String id : requested) addItem(items, id, policy);
        }
        return HttpResponses.okJSON(new JSONObject()
                .element("items", items)
                .element("refreshSeconds", policy.getBehavior().getRefreshSeconds()));
    }

    private static String[] requestedIds(String ids) {
        if (ids == null || !ids.matches("\\d{1,18}(,\\d{1,18}){0,7}")) {
            throw org.kohsuke.stapler.HttpResponses.errorWithoutStack(400, "Provide up to eight queue item IDs.");
        }
        return java.util.Arrays.stream(ids.split(",")).distinct().toArray(String[]::new);
    }

    private static void addItem(JSONObject items, String id, NodePropertyPolicy policy) {
        var queued = Jenkins.get().getQueue().getItem(Long.parseLong(id));
        if (queued instanceof Queue.BuildableItem buildable && !buildable.isPending()) {
            addReadable(items, id, buildable, policy);
        }
    }

    private static void addReadable(
            JSONObject items, String id, Queue.BuildableItem buildable, NodePropertyPolicy policy) {
        if (buildable.task instanceof Item job && job.hasPermission(Item.READ)) {
            items.element(id, messages(buildable, policy));
        }
    }

    static List<JSONObject> messages(Queue.BuildableItem item, NodePropertyPolicy policy) {
        var messages = new ArrayList<JSONObject>();
        for (Node node : candidates(item).stream()
                .sorted(java.util.Comparator.comparing(Node::getNodeName))
                .toList()) {
            var message = message(node, item, policy);
            // Leave the native message intact if any candidate may run the job, or has another kind of rule.
            if (message.isEmpty()) return List.of();
            messages.add(message);
        }
        return messages;
    }

    private static Set<Node> candidates(Queue.BuildableItem item) {
        var label = item.getAssignedLabel();
        if (label == null) return Set.of();
        var nodes = label.getNodes();
        return nodes.size() > 32 ? Set.of() : nodes;
    }

    private static JSONObject message(Node node, Queue.BuildableItem item, NodePropertyPolicy policy) {
        var computer = node.toComputer();
        if (computer == null || computer.isOffline()) return new JSONObject();
        String reason = reason(node, item, policy);
        return reason == null ? new JSONObject() : message(node, computer, reason);
    }

    private static String reason(Node node, Queue.BuildableItem item, NodePropertyPolicy policy) {
        String problem = policy.getBehavior().isBlockBuilds() ? policy.violation(node) : null;
        return problem == null ? nameReason(node, item) : "To run builds on this node: " + problem;
    }

    private static JSONObject message(Node node, Computer computer, String reason) {
        var message = new JSONObject().element("text", "Node “" + shortText(node.getNodeName()) + "”: " + reason);
        if (node.hasPermission(Computer.CONFIGURE)) {
            message.element(
                    "url", Stapler.getCurrentRequest2().getContextPath() + "/" + computer.getUrl() + "configure");
        }
        return message;
    }

    static String nameReason(Node node, Queue.BuildableItem item) {
        return hudson.ExtensionList.lookup(PropertyRequirement.class).stream()
                .map(provider -> provider.queueReason(node, item))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    static String shortText(String text) {
        return text.length() <= 160 ? text : text.substring(0, 157) + "…";
    }
}
