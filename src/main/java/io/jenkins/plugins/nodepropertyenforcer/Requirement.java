package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.slaves.NodeProperty;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

/** A stable descriptor ID, never a user-supplied class to instantiate. */
public final class Requirement extends AbstractDescribableImpl<Requirement> {
    private final String propertyId;
    private final boolean enabled;

    public Requirement(String propertyId) {
        this(propertyId, true);
    }

    @DataBoundConstructor
    public Requirement(String propertyId, boolean enabled) {
        this.propertyId = propertyId;
        this.enabled = enabled;
    }

    public String getPropertyId() {
        return propertyId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Requirement> {
        private static final String UNAVAILABLE = "Unavailable option: ";

        @Override
        public String getDisplayName() {
            return "Required node option";
        }

        @POST
        public ListBoxModel doFillPropertyIdItems() {
            ListBoxModel options = new ListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) return options;
            options.add("Choose an option to require", "");
            NodeProperty.all()
                    .forEach(d -> options.add(
                            (NodePropertyPolicy.unavailableReason(d) == null ? "" : UNAVAILABLE) + d.getDisplayName(),
                            d.getId()));
            NodePropertyPolicy.INTEGRATIONS.stream()
                    .filter(i -> NodePropertyPolicy.descriptor(i.propertyId()) == null)
                    .forEach(i -> options.add(UNAVAILABLE + i.label(), i.propertyId()));
            NodePropertyPolicy.get().getRequirements().stream()
                    .map(Requirement::getPropertyId)
                    .filter(id -> id != null && options.stream().noneMatch(o -> o.value.equals(id)))
                    .forEach(id -> options.add(UNAVAILABLE + id, id));
            return options;
        }

        @POST
        public FormValidation doCheckPropertyId(@QueryParameter String value, @QueryParameter boolean enabled) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (!enabled || value == null || value.isBlank()) return FormValidation.ok();
            String problem = NodePropertyPolicy.unavailableReason(value);
            return problem == null ? FormValidation.ok() : FormValidation.error(problem);
        }

        @RequirePOST
        public HttpResponse doValidateRestrictionValue(@QueryParameter String value, @QueryParameter String kind) {
            Jenkins.get().checkPermission(Jenkins.READ);
            String problem = switch (kind == null ? "" : kind) {
                case "expression" -> RestrictionValues.checkExpression(value);
                case "classes" -> RestrictionValues.className(value);
                default -> "Select a supported restriction field.";
            };
            return HttpResponses.okJSON(new JSONObject().element("message", problem == null ? "" : problem));
        }
    }
}
