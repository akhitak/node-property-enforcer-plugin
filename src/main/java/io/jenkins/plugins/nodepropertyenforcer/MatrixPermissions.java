package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.Node;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.security.PermissionScope;
import hudson.slaves.NodeProperty;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.NodeListener;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;

/** All grants are confined to this node and use an authenticated user, never a supplied creator ID. */
@Extension(optional = true, ordinal = -100)
public final class MatrixPermissions extends NodeListener implements PropertyRequirement {
    static final String MESSAGE = "Select at least one node permission for a user or group.";

    @Override
    public String propertyId() {
        return AuthorizationMatrixNodeProperty.class.getName();
    }

    static List<Permission> nodePermissions() {
        return Permission.getAll().stream()
                .filter(p -> p.getEnabled() && p.isContainedBy(PermissionScope.COMPUTER))
                .toList();
    }

    static String creator() {
        var authentication = Jenkins.getAuthentication2();
        if (ACL.isAnonymous2(authentication) || ACL.SYSTEM2.equals(authentication)) return null;
        User user = User.current();
        return user == null ? null : user.getId();
    }

    @Override
    public String check(NodeProperty<?> property) {
        var grants = ((AuthorizationMatrixNodeProperty) property).getGrantedPermissionEntries();
        return nodePermissions().stream()
                        .anyMatch(p -> grants.getOrDefault(p, java.util.Set.of()).stream()
                                .anyMatch(entry -> !entry.getSid().isBlank()))
                ? null
                : MESSAGE;
    }

    @Override
    public String checkSubmitted(JSONObject property, boolean creating) {
        if (creating && creator() != null) return null;
        JSONObject data = property.optJSONObject("data");
        if (data == null) return MESSAGE;
        return data.keySet().stream().anyMatch(key -> hasGrant(data, key)) ? null : MESSAGE;
    }

    private static boolean hasGrant(JSONObject data, String key) {
        String[] principal = key.split(":", 2);
        if (principal.length != 2
                || !java.util.Set.of("USER", "GROUP", "EITHER").contains(principal[0])
                || principal[1].isBlank()) return false;
        JSONObject grants = data.optJSONObject(key);
        return grants != null && nodePermissions().stream().anyMatch(p -> Boolean.TRUE.equals(grants.opt(p.getId())));
    }

    @Override
    public void describe(JSONObject rule) {
        rule.element("check", "matrix")
                .element("contentMessage", MESSAGE)
                .element("creator", creator())
                .element(
                        "permissions",
                        nodePermissions().stream().map(Permission::getId).toList());
    }

    @Override
    protected void onCreated(Node node) {
        String creator = creator();
        var policy = NodePropertyPolicy.get();
        if (creator == null
                || policy.enabledRequirements().stream()
                        .noneMatch(r -> propertyId().equals(r.getPropertyId()))
                || NodePropertyPolicy.unavailableReason(propertyId()) != null) return;
        var existing = node.getNodeProperty(AuthorizationMatrixNodeProperty.class);
        if (existing == null) return;
        var updated = new AuthorizationMatrixNodeProperty(Map.of());
        updated.setInheritanceStrategy(existing.getInheritanceStrategy());
        existing.getGrantedPermissionEntries()
                .forEach((permission, entries) -> entries.forEach(e -> updated.add(permission, e)));
        nodePermissions().forEach(permission -> updated.add(permission, PermissionEntry.user(creator)));
        try {
            node.getNodeProperties().replace(updated);
        } catch (IOException e) {
            Logger.getLogger(MatrixPermissions.class.getName())
                    .log(Level.WARNING, "Could not save creator node permissions.", e);
        }
    }
}
