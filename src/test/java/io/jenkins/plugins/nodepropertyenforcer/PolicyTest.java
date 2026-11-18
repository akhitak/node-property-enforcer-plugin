package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.JNLPLauncher;
import hudson.util.FormValidation;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class PolicyTest {
    private JenkinsRule j;
    private NodePropertyPolicy policy;
    private static final String MATRIX = AuthorizationMatrixNodeProperty.class.getName();
    private static final String JOBS = JobRestrictionProperty.class.getName();
    private static final String ENV = EnvironmentVariablesNodeProperty.class.getName();

    @BeforeEach
    void setup(JenkinsRule rule) {
        j = rule;
        policy = NodePropertyPolicy.get();
        var strategy = new ProjectMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, "anonymous");
        j.jenkins.setAuthorizationStrategy(strategy);
    }

    private void require(String... ids) throws Exception {
        policy.replace(java.util.Arrays.stream(ids).map(Requirement::new).toList());
    }

    private DumbSlave node(String name) throws Exception {
        return new DumbSlave(name, "agent-work", new JNLPLauncher());
    }

    @Test
    void togglesAndPersistsBothPluginProperties() throws Exception {
        var node = node("agent");
        assertNull(policy.violation(node));
        require(MATRIX, JOBS);
        assertTrue(policy.violation(node).contains("node-based security"));
        assertTrue(policy.violation(node).contains("Restrict jobs"), "Every missing required property is reported");
        node.getNodeProperties()
                .add(new AuthorizationMatrixNodeProperty(Map.of(
                        Computer.BUILD,
                        java.util.Set.of(org.jenkinsci.plugins.matrixauth.PermissionEntry.user("node-owner")))));
        assertTrue(policy.violation(node).contains("Restrict jobs"));
        node.getNodeProperties()
                .add(new JobRestrictionProperty(
                        new com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction(
                                "approved-.*", false)));
        assertNull(policy.violation(node));
        policy.load();
        assertEquals(2, policy.getRequirements().size());
        require();
        assertNull(policy.violation(node("plain")));
    }

    @Test
    void discoversCoreAndPluginProperties() {
        var descriptor = j.jenkins.getDescriptorByType(Requirement.DescriptorImpl.class);
        var options = descriptor.doFillPropertyIdItems();
        for (String id : List.of(MATRIX, JOBS, ENV)) {
            assertTrue(options.stream().anyMatch(o -> o.value.equals(id)));
        }
    }

    @Test
    void matrixReadinessRequiresProjectBasedAuthorization() throws Exception {
        require(ENV);
        var descriptor = j.jenkins.getDescriptorByType(Requirement.DescriptorImpl.class);
        for (String empty : new String[] {null, "", " "}) {
            assertEquals(FormValidation.Kind.OK, descriptor.doCheckPropertyId(empty, true).kind);
            assertEquals(FormValidation.Kind.OK, descriptor.doCheckPropertyId(empty, false).kind);
        }
        assertNull(NodePropertyPolicy.unavailableReason(MATRIX));
        var global = new GlobalMatrixAuthorizationStrategy();
        global.add(Jenkins.ADMINISTER, "anonymous");
        for (var strategy : List.of(AuthorizationStrategy.UNSECURED, global)) {
            j.jenkins.setAuthorizationStrategy(strategy);
            assertNotNull(NodePropertyPolicy.descriptor(MATRIX));
            assertTrue(NodePropertyPolicy.unavailableReason(MATRIX)
                    .contains("Project-based Matrix Authorization Strategy"));
            assertTrue(descriptor.doFillPropertyIdItems().stream()
                    .anyMatch(o -> o.value.equals(MATRIX) && o.name.startsWith("Unavailable option:")));
            assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckPropertyId(MATRIX, true).kind);
            assertEquals(FormValidation.Kind.OK, descriptor.doCheckPropertyId(MATRIX, false).kind);
            assertThrows(FormException.class, () -> require(MATRIX));
            assertEquals(ENV, policy.getRequirements().getFirst().getPropertyId());
        }
        var project = new ProjectMatrixAuthorizationStrategy();
        project.add(Jenkins.ADMINISTER, "anonymous");
        j.jenkins.setAuthorizationStrategy(project);
        assertNull(NodePropertyPolicy.unavailableReason(MATRIX));
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckPropertyId(MATRIX, true).kind);
        require(MATRIX);
    }

    @Test
    void globalValidationFollowsOnlyTheSelectedOptionAndIgnoresEmptyRows() throws Exception {
        require(JOBS, MATRIX);
        j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
        try (var client = j.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(true);
            var page = client.goTo("configure");
            client.waitForBackgroundJavaScript(1000);
            HtmlElement section =
                    page.getFirstByXPath("//div[normalize-space(text())='Required node properties']/parent::section");
            assertNotNull(section);
            assertNull(section.querySelector(".jenkins-alert-warning"));
            assertFalse(section.asNormalizedText().contains("Optional plugin support"));
            assertTrue(section.asNormalizedText().contains("needs Project-based Matrix Authorization Strategy"));
            List<HtmlSelect> choices = section.getByXPath(".//select[@name='_.propertyId']");
            choices.get(1).setSelectedAttribute(ENV, true);
            client.waitForBackgroundJavaScript(1000);
            assertFalse(section.asNormalizedText().contains("needs Project-based Matrix Authorization Strategy"));
            assertEquals(
                    JOBS, choices.getFirst().getSelectedOptions().getFirst().getValueAttribute());
            choices.get(1).setSelectedAttribute("", true);
            client.waitForBackgroundJavaScript(1000);
            assertFalse(section.asNormalizedText().contains("This option is unavailable"));
            assertEquals("", choices.get(1).getSelectedOptions().getFirst().getValueAttribute());
            j.submit(page.getFormByName("config"));
        }
        assertEquals(
                List.of(JOBS),
                policy.getRequirements().stream()
                        .map(Requirement::getPropertyId)
                        .toList());
    }

    @Test
    void savedMatrixRuleStaysVisibleAndCanBePausedAfterStrategyChanges() throws Exception {
        require(MATRIX, JOBS);
        var agent = node("configured");
        agent.getNodeProperties()
                .add(new AuthorizationMatrixNodeProperty(Map.of(
                        Computer.BUILD,
                        java.util.Set.of(org.jenkinsci.plugins.matrixauth.PermissionEntry.user("node-owner")))));
        agent.getNodeProperties()
                .add(new JobRestrictionProperty(
                        new com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction(
                                "approved-.*", false)));
        assertNull(policy.violation(agent));
        j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
        policy.load();
        assertTrue(policy.violation(agent).contains("Project-based Matrix Authorization Strategy"));
        var descriptor = j.jenkins.getDescriptorByType(Requirement.DescriptorImpl.class);
        assertTrue(descriptor.doFillPropertyIdItems().stream()
                .anyMatch(o -> o.value.equals(MATRIX) && o.name.contains("Unavailable option")));
        policy.replace(List.of(new Requirement(MATRIX, false), new Requirement(JOBS)));
        assertNull(policy.violation(agent));
        assertEquals(JOBS, policy.enabledRequirements().getFirst().getPropertyId());
    }

    @Test
    void invalidPolicyCannotReplaceValidPolicy() throws Exception {
        require(ENV);
        assertThrows(FormException.class, () -> require("java.lang.Runtime"));
        assertThrows(FormException.class, () -> require(ENV, ENV));
        assertEquals(ENV, policy.getRequirements().getFirst().getPropertyId());
        var requirements = policy.getRequirements();
        assertThrows(UnsupportedOperationException.class, requirements::clear);
    }

    @Test
    void requiresAdministratorForConfigurationAndDiscovery() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Computer.CREATE, Computer.CONFIGURE)
                .everywhere()
                .to("creator"));
        var form = new JSONObject();
        var descriptor = j.jenkins.getDescriptorByType(Requirement.DescriptorImpl.class);
        try (var ignored = ACL.as2(hudson.model.User.getById("creator", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> require(ENV));
            assertThrows(
                    AccessDeniedException.class,
                    () -> policy.configure((org.kohsuke.stapler.StaplerRequest2) null, form));
            assertThrows(AccessDeniedException.class, descriptor::doFillPropertyIdItems);
            assertThrows(AccessDeniedException.class, () -> descriptor.doCheckPropertyId(MATRIX, true));
        }
    }

    @Test
    void corruptedAndUnavailableSavedPoliciesFailClosed() throws Exception {
        require(ENV);
        var file = j.jenkins.getRootDir().toPath().resolve(NodePropertyPolicy.class.getName() + ".xml");
        String saved = Files.readString(file);
        Files.writeString(file, saved.replace(ENV, "missing.plugin.Property"));
        policy.load();
        assertTrue(policy.violation(node("agent")).contains("unavailable"));
        Files.writeString(file, "<broken>");
        policy.load();
        assertNotNull(policy.violation(node("agent")));
        require();
        assertNull(policy.violation(node("agent")));
    }

    @Test
    void nativeGlobalFormRoundTripAndRemoval() throws Exception {
        require(ENV, JOBS);
        try (var client = j.createWebClient()) {
            var form = client.goTo("configure").getFormByName("config");
            j.submit(form);
        }
        policy.load();
        assertEquals(2, policy.getRequirements().size());
        require();
        try (var client = j.createWebClient()) {
            j.submit(client.goTo("configure").getFormByName("config"));
        }
        assertTrue(policy.getRequirements().isEmpty());
    }

    @Test
    void creationRejectsOmissionWithoutRegisteringOrWritingNode() throws Exception {
        require(ENV);
        var response = create("rejected", "{}");
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getContentAsString().contains("Environment variables"));
        assertNull(j.jenkins.getNode("rejected"));
        assertFalse(Files.exists(j.jenkins.getRootDir().toPath().resolve("nodes/rejected/config.xml")));
        assertEquals("nosniff", response.getResponseHeaderValue("X-Content-Type-Options"));
        assertTrue(response.getResponseHeaderValue("Cache-Control").contains("no-store"));
        assertTrue(response.getContentType().startsWith("text/html"));
    }

    @Test
    void compliantCreationAndSubsequentRemoval() throws Exception {
        require(ENV);
        String properties = "[{\"stapler-class\":\"" + ENV + "\"}]";
        assertEquals(200, create("accepted", properties).getStatusCode());
        assertNotNull(j.jenkins.getNode("accepted").getNodeProperty(EnvironmentVariablesNodeProperty.class));
        var before = j.jenkins.getNode("accepted");
        assertEquals(
                400,
                post("computer/accepted/configSubmit", form("accepted", "{}")).getStatusCode());
        assertSame(before, j.jenkins.getNode("accepted"));
        require();
        assertEquals(200, create("unrestricted", "{}").getStatusCode());
    }

    @Test
    void nativeClassBagCreationAndNodeConfigurationRoundTrip() throws Exception {
        require(ENV, JOBS);
        String bag = "{\"stapler-class-bag\":\"true\",\""
                + NodePropertyPolicy.descriptor(ENV).getJsonSafeClassName() + "\":{},\""
                + NodePropertyPolicy.descriptor(JOBS).getJsonSafeClassName()
                + "\":{\"jobRestriction\":{\"stapler-class\":\"com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction\",\"regexExpression\":\"approved-.*\",\"checkShortName\":false}}}";
        assertEquals(200, create("native-agent", bag).getStatusCode());
        var agent = (DumbSlave) j.jenkins.getNode("native-agent");
        assertNotNull(agent.getNodeProperty(JobRestrictionProperty.class));
        try (var client = j.createWebClient()) {
            client.getOptions().setThrowExceptionOnScriptError(false);
            j.submit(client.goTo("computer/native-agent/configure").getFormByName("config"));
        }
        assertNotNull(j.jenkins.getNode("native-agent").getNodeProperty(JobRestrictionProperty.class));
    }

    @Test
    void cannotCopyNoncompliantAgentThroughEitherRoute() throws Exception {
        j.jenkins.addNode(node("source"));
        require(ENV);
        var copy = List.of(
                new NameValuePair("name", "copy"),
                new NameValuePair("mode", "copy"),
                new NameValuePair("from", "source"));
        for (String prefix : List.of("computer/", "manage/computer/")) {
            assertEquals(400, post(prefix + "createItem", copy).getStatusCode());
        }
        assertNull(j.jenkins.getNode("copy"));
        j.jenkins.getNode("source").getNodeProperties().add(new EnvironmentVariablesNodeProperty());
        assertEquals(200, post("computer/createItem", copy).getStatusCode());
        assertNotNull(j.jenkins.getNode("copy"));
    }

    @Test
    void xmlMutationCannotBypassFormRequirements() throws Exception {
        j.jenkins.addNode(node("existing"));
        require(ENV);
        try (var client = j.createWebClient()) {
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            for (String path : List.of("computer/createItem", "computer/existing/config.xml")) {
                var request = new WebRequest(client.createCrumbedUrl(path), HttpMethod.POST);
                request.setAdditionalHeader("Content-Type", "application/xml");
                request.setRequestBody("<!DOCTYPE slave [<!ENTITY x SYSTEM 'file:///must-not-read'>]><slave/>");

                assertEquals(400, client.getPage(request).getWebResponse().getStatusCode());
            }
        }
        assertEquals(1, j.jenkins.getNodes().size());
    }

    @Test
    void directProvisioningIsBlockedAtSchedulingAndControllerExempt() throws Exception {
        var node = node("via-api");
        j.jenkins.addNode(node);
        require(ENV);
        var dispatcher = new PolicyQueueDispatcher();
        assertNotNull(dispatcher.canTake(node, (hudson.model.Queue.BuildableItem) null));
        assertTrue(dispatcher
                .canTake(node, (hudson.model.Queue.BuildableItem) null)
                .getShortDescription()
                .contains("Environment variables"));
        assertNull(dispatcher.canTake(j.jenkins, (hudson.model.Queue.BuildableItem) null));
        node.getNodeProperties().add(new EnvironmentVariablesNodeProperty());
        assertNull(dispatcher.canTake(node, (hudson.model.Queue.BuildableItem) null));
    }

    @Test
    void nullFalseArrayAndDuplicatePropertyFormsAreRejected() throws Exception {
        require(ENV);
        String key = NodePropertyPolicy.descriptor(ENV).getJsonSafeClassName();
        for (String value : List.of("null", "false", "[]", "\"checked\"")) {
            assertEquals(400, create("bad", "{\"" + key + "\":" + value + "}").getStatusCode());
        }
        assertEquals(
                400, create("bad", "{\"" + key + "\":{},\"" + key + "\":null}").getStatusCode());
        assertNull(j.jenkins.getNode("bad"));
    }

    private WebResponse create(String name, String properties) throws Exception {
        return post("computer/doCreateItem?name=" + name + "&type=hudson.slaves.DumbSlave", form(name, properties));
    }

    private List<NameValuePair> form(String name, String properties) {
        properties = properties.equals("{}") ? "[]" : properties;
        return List.of(new NameValuePair(
                "json",
                "{\"name\":\"" + name
                        + "\",\"nodeDescription\":\"\",\"numExecutors\":\"1\",\"remoteFS\":\"agent-work\","
                        + "\"labelString\":\"\",\"mode\":\"NORMAL\",\"retentionStrategy\":{\"stapler-class\":\"hudson.slaves.RetentionStrategy$Always\"},"
                        + "\"launcher\":{\"stapler-class\":\"hudson.slaves.JNLPLauncher\"},\"nodeProperties\":"
                        + properties + "}"));
    }

    private WebResponse post(String path, List<NameValuePair> values) throws Exception {
        try (var client = j.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(false);
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            var request = new WebRequest(client.createCrumbedUrl(path), HttpMethod.POST);
            request.setRequestParameters(values);

            return client.getPage(request).getWebResponse();
        }
    }
}
