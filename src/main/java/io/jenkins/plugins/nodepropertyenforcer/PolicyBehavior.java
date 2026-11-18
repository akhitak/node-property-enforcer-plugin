package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/** Immutable, independently selectable existing-agent rollout controls. */
public final class PolicyBehavior extends AbstractDescribableImpl<PolicyBehavior> {
    static final String REFRESH_MESSAGE = "Enter a whole number from 15 to 300 seconds.";
    private final boolean blockBuilds;
    private final boolean enforceEdits;
    private final boolean showNodeWarnings;
    private final boolean showBuildWarnings;
    private final int refreshSeconds;

    @DataBoundConstructor
    public PolicyBehavior(
            boolean blockBuilds,
            boolean enforceEdits,
            boolean showNodeWarnings,
            boolean showBuildWarnings,
            int refreshSeconds) {
        if (refreshSeconds < 15 || refreshSeconds > 300) {
            throw new IllegalArgumentException(REFRESH_MESSAGE);
        }
        this.blockBuilds = blockBuilds;
        this.enforceEdits = enforceEdits;
        this.showNodeWarnings = showNodeWarnings;
        this.showBuildWarnings = showBuildWarnings;
        this.refreshSeconds = refreshSeconds;
    }

    static PolicyBehavior defaults() {
        return new PolicyBehavior(true, true, true, true, 30);
    }

    public boolean isBlockBuilds() {
        return blockBuilds;
    }

    public boolean isEnforceEdits() {
        return enforceEdits;
    }

    public boolean isShowNodeWarnings() {
        return showNodeWarnings;
    }

    public boolean isShowBuildWarnings() {
        return showBuildWarnings;
    }

    public int getRefreshSeconds() {
        return refreshSeconds;
    }

    static FormValidation validateRefreshSeconds(String value) {
        try {
            int seconds = Integer.parseInt(value);
            return seconds >= 15 && seconds <= 300 ? FormValidation.ok() : FormValidation.error(REFRESH_MESSAGE);
        } catch (NumberFormatException e) {
            return FormValidation.error(REFRESH_MESSAGE);
        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<PolicyBehavior> {
        @org.kohsuke.stapler.verb.POST
        public FormValidation doCheckRefreshSeconds(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return validateRefreshSeconds(value);
        }

        @Override
        public String getDisplayName() {
            return "Existing-agent behavior";
        }
    }
}
