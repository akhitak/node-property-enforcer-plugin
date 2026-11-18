package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.Node;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;

/** Atomically published policy; only administrators may replace it. */
@Extension
public final class NodePropertyPolicy extends GlobalConfiguration {
    static final String MATRIX = "org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty";
    static final List<Integration> INTEGRATIONS = List.of(
            new Integration("matrix-auth", "3.2.10", MATRIX, "Enable node-based security"),
            new Integration(
                    "job-restrictions",
                    "216.v0732cc7fb_076",
                    "com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty",
                    "Restrict jobs execution at node"));
    private List<Requirement> requirements = List.of();
    private PolicyBehavior behavior = PolicyBehavior.defaults();
    // Jenkins persists this class using XStream, which honors transient fields.
    @SuppressWarnings("java:S2065")
    private final transient java.util.concurrent.atomic.AtomicReference<State> active =
            new java.util.concurrent.atomic.AtomicReference<>(new State(List.of(), PolicyBehavior.defaults()));

    private record State(List<Requirement> requirements, PolicyBehavior behavior) {}

    public NodePropertyPolicy() {
        load();
    }

    public static NodePropertyPolicy get() {
        return GlobalConfiguration.all().get(NodePropertyPolicy.class);
    }

    public List<Requirement> getRequirements() {
        return active.get().requirements();
    }

    public PolicyBehavior getBehavior() {
        return active.get().behavior();
    }

    public List<Requirement> enabledRequirements() {
        return active.get().requirements().stream()
                .filter(Requirement::isEnabled)
                .toList();
    }

    @Override
    public synchronized void load() {
        if (!getConfigFile().exists()) {
            active.set(new State(List.of(), PolicyBehavior.defaults()));
            return;
        }
        try {
            getConfigFile().unmarshal(this);
            active.set(new State(List.copyOf(requirements), Objects.requireNonNull(behavior)));
        } catch (IOException | RuntimeException e) {
            active.set(new State(List.of(new Requirement("unreadable-policy")), PolicyBehavior.defaults()));
            java.util.logging.Logger.getLogger(NodePropertyPolicy.class.getName())
                    .log(
                            java.util.logging.Level.WARNING,
                            "Cannot load required node properties; enforcement fails closed.",
                            e);
        }
    }

    @Override
    public String getDisplayName() {
        return "Required node properties";
    }

    @Override
    public synchronized boolean configure(StaplerRequest2 request, JSONObject json) throws FormException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        JSONObject options = json.optJSONObject("behavior");
        String refresh = options == null ? null : options.optString("refreshSeconds", null);
        if (PolicyBehavior.validateRefreshSeconds(refresh).kind != FormValidation.Kind.OK) {
            throw new FormException(PolicyBehavior.REFRESH_MESSAGE, "refreshSeconds");
        }
        replace(
                request.bindJSONToList(Requirement.class, json.get("requirements")).stream()
                        .filter(r ->
                                r.getPropertyId() != null && !r.getPropertyId().isBlank())
                        .toList(),
                request.bindJSON(PolicyBehavior.class, options));
        return true;
    }

    public synchronized void replace(List<Requirement> proposed) throws FormException {
        replace(proposed, getBehavior());
    }

    public synchronized void replace(List<Requirement> proposed, PolicyBehavior options) throws FormException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        List<Requirement> checked = List.copyOf(proposed);
        validateRequirements(checked);
        requirements = checked;
        behavior = Objects.requireNonNull(options);
        try {
            getConfigFile().write(this);
            active.set(new State(checked, behavior));
        } catch (IOException e) {
            requirements = active.get().requirements();
            behavior = active.get().behavior();
            throw new FormException(
                    "Could not save these settings. Your previous settings are unchanged.", e, "requirements");
        }
    }

    private static void validateRequirements(List<Requirement> checked) throws FormException {
        for (Requirement requirement : checked) {
            String problem = unavailableReason(requirement.getPropertyId());
            if (requirement.isEnabled() && problem != null) {
                throw new FormException(
                        problem + " Uncheck 'Require this option' to keep it inactive.", "requirements");
            }
        }
        if (checked.stream().map(Requirement::getPropertyId).distinct().count() != checked.size()) {
            throw new FormException("Each option can be added only once. Delete the duplicate entry.", "requirements");
        }
    }

    static NodePropertyDescriptor descriptor(String id) {
        return NodeProperty.all().stream()
                .filter(d -> Objects.equals(d.getId(), id))
                .findFirst()
                .orElse(null);
    }

    static String unavailableReason(String id) {
        var descriptor = descriptor(id);
        return descriptor == null ? missingReason(id) : unavailableReason(descriptor);
    }

    private static String missingReason(String id) {
        return INTEGRATIONS.stream()
                .filter(i -> i.propertyId().equals(id))
                .findFirst()
                .map(Integration::missingMessage)
                .orElse(
                        "This option is unavailable. Check its plugin in Manage Jenkins → Plugins, or choose another option.");
    }

    static String unavailableReason(NodePropertyDescriptor descriptor) {
        if (descriptor == null) {
            return missingReason(null);
        }
        if (MATRIX.equals(descriptor.getId()) && !descriptor.isApplicable(Node.class)) {
            return "Enable node-based security needs Project-based Matrix Authorization Strategy under Manage Jenkins → Security → Authorization.";
        }
        return null;
    }

    /** Also usable by provisioning plugins before calling Jenkins.addNode. Null means compliant. */
    public String violation(Node node) {
        if (node == Jenkins.get()) {
            return null;
        }
        return violation(enabledRequirements(), d -> checkProperty(node, d));
    }

    private static String checkProperty(Node node, NodePropertyDescriptor descriptor) {
        var property = node.getNodeProperties().get(descriptor);
        if (!descriptor.isApplicable(node.getClass()) || property == null) return missing(descriptor);
        var check = PropertyRequirement.find(descriptor.getId());
        return check == null ? null : check.check(property);
    }

    static String missing(NodePropertyDescriptor descriptor) {
        return "Turn on “" + descriptor.getDisplayName() + "” under Node Properties.";
    }

    static String violation(
            List<Requirement> rules, java.util.function.Function<NodePropertyDescriptor, String> check) {
        String problems = rules.stream()
                .map(rule -> checkRequirement(rule.getPropertyId(), check))
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" "));
        return problems.isEmpty() ? null : problems;
    }

    private static String checkRequirement(
            String id, java.util.function.Function<NodePropertyDescriptor, String> check) {
        NodePropertyDescriptor descriptor = descriptor(id);
        if (descriptor == null) return missingReason(id);
        String problem = unavailableReason(descriptor);
        return problem == null ? check.apply(descriptor) : problem;
    }
}
