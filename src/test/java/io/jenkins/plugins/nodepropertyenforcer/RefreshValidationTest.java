package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Descriptor.FormException;
import hudson.model.User;
import hudson.security.ACL;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.util.FormValidation;
import java.nio.file.Files;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class RefreshValidationTest {
    @Test
    void validatesRangeAndRequiresAdministrator(JenkinsRule j) {
        var descriptor = j.jenkins.getDescriptorByType(PolicyBehavior.DescriptorImpl.class);
        for (String value : List.of("15", "30", "300")) {
            assertEquals(FormValidation.Kind.OK, descriptor.doCheckRefreshSeconds(value).kind);
        }
        for (String value : List.of("", " ", "14", "301", "15.5", "abc", "2147483648")) {
            assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRefreshSeconds(value).kind);
        }
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRefreshSeconds(null).kind);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));
        try (var ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> descriptor.doCheckRefreshSeconds("30"));
        }
    }

    @Test
    void invalidOrMissingRefreshPreservesSavedPolicy(JenkinsRule j) throws Exception {
        var policy = NodePropertyPolicy.get();
        String property = EnvironmentVariablesNodeProperty.class.getName();
        policy.replace(List.of(new Requirement(property)));
        var file = j.jenkins.getRootDir().toPath().resolve(NodePropertyPolicy.class.getName() + ".xml");
        String saved = Files.readString(file);
        for (String value : List.of("", " ", "14", "301", "15.5", "abc", "2147483648")) {
            var options = new JSONObject();
            options.put("refreshSeconds", value);
            assertInvalid(policy, new JSONObject().element("behavior", options));
        }
        assertInvalid(policy, new JSONObject().element("behavior", new JSONObject()));
        assertInvalid(policy, new JSONObject());
        assertEquals(saved, Files.readString(file));
        assertEquals(property, policy.getRequirements().getFirst().getPropertyId());
        assertEquals(30, policy.getBehavior().getRefreshSeconds());
    }

    private static void assertInvalid(NodePropertyPolicy policy, JSONObject form) {
        var error = assertThrows(
                FormException.class, () -> policy.configure((org.kohsuke.stapler.StaplerRequest2) null, form));
        assertEquals(PolicyBehavior.REFRESH_MESSAGE, error.getMessage());
    }

    @Test
    void nativeSaveShowsHelpfulValidationInsteadOfStackTrace(JenkinsRule j) throws Exception {
        var policy = NodePropertyPolicy.get();
        policy.replace(List.of(new Requirement(EnvironmentVariablesNodeProperty.class.getName())));
        try (var client = j.createWebClient()) {
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            var form = client.goTo("configure").getFormByName("config");
            var refresh = form.getInputByName("_.refreshSeconds");
            refresh.removeAttribute("min");
            refresh.setValueAttribute("14");
            var result = j.submit(form);
            String body = result.getWebResponse().getContentAsString();
            assertTrue(body.contains(PolicyBehavior.REFRESH_MESSAGE));
            assertFalse(body.contains("Oops!"));
            assertFalse(body.contains("IllegalArgumentException"));
        }
        assertEquals(30, policy.getBehavior().getRefreshSeconds());
        policy.load();
        assertEquals(30, policy.getBehavior().getRefreshSeconds());
    }
}
