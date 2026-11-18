package io.jenkins.plugins.nodepropertyenforcer;

/** Dependency metadata contains only strings, so absent integrations cannot prevent class loading. */
record Integration(String pluginId, String minimum, String propertyId, String label) {
    String missingMessage() {
        return "Install or enable " + pluginId + " (" + minimum
                + " or newer) in Manage Jenkins → Plugins, then restart Jenkins.";
    }
}
