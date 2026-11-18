package io.jenkins.plugins.nodepropertyenforcer;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.slaves.NodeProperty;
import net.sf.json.JSONObject;

/** Optional providers validate values without binding arbitrary submitted classes. */
public interface PropertyRequirement extends ExtensionPoint {
    String propertyId();

    String check(NodeProperty<?> property);

    String checkSubmitted(JSONObject property, boolean creating);

    default void describe(JSONObject rule) {}

    default String queueReason(Node node, Queue.BuildableItem item) {
        return null;
    }

    static PropertyRequirement find(String id) {
        return ExtensionList.lookup(PropertyRequirement.class).stream()
                .filter(check -> check.propertyId().equals(id))
                .findFirst()
                .orElse(null);
    }
}
