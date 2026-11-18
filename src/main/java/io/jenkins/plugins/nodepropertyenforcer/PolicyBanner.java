package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.ComputerSet;
import hudson.model.PageDecorator;
import hudson.model.Run;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/** Adds one small status banner to node and build HTML pages, including the classic console. */
@Extension
public final class PolicyBanner extends PageDecorator {
    /** Escaped form metadata; enforcement still runs on the server. */
    public String getFormPolicy() {
        var request = Stapler.getCurrentRequest2();
        if (request == null || !Jenkins.get().hasPermission(Jenkins.READ)) {
            return null;
        }
        return isAgentPage(request) ? formPolicy() : null;
    }

    private static boolean isAgentPage(StaplerRequest2 request) {
        var computer = request.findAncestorObject(Computer.class);
        if (computer != null) {
            return computer.getNode() != Jenkins.get();
        }
        return request.findAncestorObject(ComputerSet.class) != null;
    }

    private static String formPolicy() {
        var policy = NodePropertyPolicy.get();
        var rules = policy.enabledRequirements().stream()
                .map(PolicyBanner::formRule)
                .toList();
        return new JSONObject()
                .element("checkEdits", policy.getBehavior().isEnforceEdits())
                .element("rules", rules)
                .toString();
    }

    private static JSONObject formRule(Requirement rule) {
        var descriptor = NodePropertyPolicy.descriptor(rule.getPropertyId());
        var item = new JSONObject()
                .element("name", descriptor == null ? "" : descriptor.getJsonSafeClassName())
                .element("label", descriptor == null ? "Required option" : descriptor.getDisplayName())
                .element("problem", NodePropertyPolicy.unavailableReason(rule.getPropertyId()));
        var check = PropertyRequirement.find(rule.getPropertyId());
        if (check != null) check.describe(item);
        return item;
    }

    public String getStatusUrl() {
        var request = Stapler.getCurrentRequest2();
        if (request == null) {
            return null;
        }
        var run = request.findAncestorObject(Run.class);
        if (run != null) {
            return request.getContextPath() + "/" + run.getUrl() + "node-property-status/";
        }
        var computer = request.findAncestorObject(Computer.class);
        return computer == null ? null : request.getContextPath() + "/" + computer.getUrl() + "node-property-status/";
    }
}
