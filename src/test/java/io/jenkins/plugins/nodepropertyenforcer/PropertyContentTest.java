package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.JobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.AnyJobRestriction;
import hudson.model.Computer;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritGlobalStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PropertyContentTest {
    private JenkinsRule j;
    private NodePropertyPolicy policy;
    private ProjectMatrixAuthorizationStrategy strategy;
    private static final String MATRIX = AuthorizationMatrixNodeProperty.class.getName();
    private static final String JOBS = JobRestrictionProperty.class.getName();

    @BeforeEach
    void setup(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        strategy = new ProjectMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(strategy);
        policy = NodePropertyPolicy.get();
        policy.replace(List.of(new Requirement(MATRIX), new Requirement(JOBS)));
    }

    @Test
    void checkedDefaultsCannotTakeBuildsAndValidContentRestoresEligibility() throws Exception {
        var node = new DumbSlave("existing", "agent-work", new JNLPLauncher());
        node.getNodeProperties().add(new AuthorizationMatrixNodeProperty(Map.of()));
        node.getNodeProperties().add(new JobRestrictionProperty(new AnyJobRestriction()));
        j.jenkins.addNode(node);
        assertEquals(MatrixPermissions.MESSAGE + " " + JobRestrictions.MESSAGE, policy.violation(node));
        assertNotNull(new PolicyQueueDispatcher().canTake(node, (hudson.model.Queue.BuildableItem) null));
        node.getNodeProperties()
                .replace(new AuthorizationMatrixNodeProperty(
                        Map.of(Computer.BUILD, Set.of(PermissionEntry.group("builders")))));
        assertEquals(JobRestrictions.MESSAGE, policy.violation(node));
        node.getNodeProperties().replace(new JobRestrictionProperty(new RegexNameRestriction("approved-.*", false)));
        assertNull(policy.violation(node));
        assertNull(new PolicyQueueDispatcher().canTake(node, (hudson.model.Queue.BuildableItem) null));
    }

    @Test
    void creatorGetsOnlyNodePermissionsUsingAuthenticatedIdentityAndEditsDoNotRefill() throws Exception {
        for (String user : List.of("local.user", "sso.user@example.test", "DOMAIN\\ldap.user")) {
            strategy.add(Jenkins.READ, PermissionEntry.user(user));
            strategy.add(Computer.CREATE, PermissionEntry.user(user));
            String name = "agent-" + j.jenkins.getNodes().size();
            assertEquals(
                    200,
                    post(
                                    user,
                                    "computer/doCreateItem?name=" + name + "&type=hudson.slaves.DumbSlave",
                                    form(name, restriction(RegexNameRestriction.class.getName())))
                            .getStatusCode());
            var node = j.jenkins.getNode(name);
            assertNotNull(node);
            var property = node.getNodeProperty(AuthorizationMatrixNodeProperty.class);
            for (var permission : MatrixPermissions.nodePermissions()) {
                assertTrue(property.hasExplicitPermission(PermissionEntry.user(user), permission));
            }
            assertFalse(property.getGrantedPermissionEntries().containsKey(Jenkins.ADMINISTER));
            assertFalse(property.getGrantedPermissionEntries().containsKey(Computer.CREATE));
            assertTrue(property.getAllPermissionEntries().stream()
                    .allMatch(entry -> entry.equals(PermissionEntry.user(user))));
            try (var ignored = ACL.as2(User.getById(user, false).impersonate2())) {
                assertFalse(j.jenkins.hasPermission(Jenkins.ADMINISTER));
            }
            assertEquals(
                    400,
                    post(
                                    user,
                                    node.toComputer().getUrl() + "configSubmit",
                                    form(name, restriction(RegexNameRestriction.class.getName())))
                            .getStatusCode());
            assertTrue(j.jenkins
                    .getNode(name)
                    .getNodeProperty(AuthorizationMatrixNodeProperty.class)
                    .hasExplicitPermission(PermissionEntry.user(user), Computer.CONFIGURE));
        }
    }

    @Test
    void directPostsCannotSaveUnrestrictedOrForgedRestrictions() throws Exception {
        for (Object restriction : List.of(
                JSONObject.fromObject("null"),
                new JSONObject(),
                restriction(AnyJobRestriction.class.getName()),
                restriction("java.lang.Runtime"))) {
            var response = post(
                    "admin",
                    "computer/doCreateItem?name=incomplete&type=hudson.slaves.DumbSlave",
                    form("incomplete", restriction));
            assertEquals(400, response.getStatusCode());
            assertTrue(response.getContentAsString().contains("Choose a job restriction"));
            assertNull(j.jenkins.getNode("incomplete"));
        }
    }

    @Test
    void anonymousCreationHasNoAutomaticPermissionGrant() throws Exception {
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("anonymous"));
        assertEquals(
                400,
                post(
                                null,
                                "computer/doCreateItem?name=anonymous-agent&type=hudson.slaves.DumbSlave",
                                form("anonymous-agent", restriction(RegexNameRestriction.class.getName())))
                        .getStatusCode());
        assertNull(j.jenkins.getNode("anonymous-agent"));
    }

    @Test
    void nativeCreationShowsCreatorAndBlocksDefaultRestrictionWithoutNavigation() throws Exception {
        try (var client = j.createWebClient().login("admin")) {
            client.getOptions().setJavaScriptEnabled(true);
            var request = new WebRequest(client.createCrumbedUrl("computer/createItem"), HttpMethod.POST);
            request.setRequestParameters(List.of(
                    new NameValuePair("name", "ui-node"), new NameValuePair("mode", "hudson.slaves.DumbSlave")));
            HtmlPage page = client.getPage(request);
            client.waitForBackgroundJavaScript(1000);
            var form = page.getFormByName("config");
            form.getInputByName("_.remoteFS").setValueAttribute("agent-work");
            HtmlCheckBoxInput matrix =
                    form.getInputByName(NodePropertyPolicy.descriptor(MATRIX).getJsonSafeClassName());
            HtmlCheckBoxInput jobs =
                    form.getInputByName(NodePropertyPolicy.descriptor(JOBS).getJsonSafeClassName());
            matrix.click();
            jobs.click();
            client.waitForBackgroundJavaScript(1000);
            var creatorRow = page.getFirstByXPath("//*[@name='[USER:admin]']");
            assertNotNull(creatorRow);
            assertTrue(page.asNormalizedText().contains(JobRestrictions.MESSAGE));
            HtmlButton save = page.getFirstByXPath("//button[@name='Submit']");
            assertSame(page, save.click());
            assertNull(j.jenkins.getNode("ui-node"));
            HtmlSelect restriction = (HtmlSelect)
                    jobs.getFirstByXPath(
                            "ancestor::div[contains(@class,'optionalBlock-container')]//select[contains(@class,'dropdownList')]");
            assertSame(restriction, page.getFocusedElement());
            int index = JobRestriction.all()
                    .indexOf(j.jenkins.getDescriptorByType(RegexNameRestriction.DescriptorImpl.class));
            restriction.setSelectedAttribute(Integer.toString(index), true);
            client.waitForBackgroundJavaScript(1000);
            form.getInputByName("_.regexExpression").type("approved-.*");
            client.waitForBackgroundJavaScript(1000);
            assertFalse(page.asNormalizedText().contains(JobRestrictions.MESSAGE));
            save.click();
            assertNotNull(j.jenkins.getNode("ui-node"));
            assertNull(policy.violation(j.jenkins.getNode("ui-node")));
        }
    }

    @Test
    void editingCannotRemoveEveryPermissionAndDoesNotAddTheEditor() throws Exception {
        var node = new DumbSlave("edit-node", "agent-work", new JNLPLauncher());
        node.getNodeProperties()
                .add(new AuthorizationMatrixNodeProperty(
                        Map.of(Computer.BUILD, Set.of(PermissionEntry.user("owner")))));
        node.getNodeProperties().add(new JobRestrictionProperty(new RegexNameRestriction("approved-.*", false)));
        j.jenkins.addNode(node);
        try (var client = j.createWebClient().login("admin")) {
            HtmlPage page = client.goTo(node.toComputer().getUrl() + "configure");
            client.waitForBackgroundJavaScript(1000);
            assertNull(page.getFirstByXPath("//*[@name='[USER:admin]']"));
            HtmlCheckBoxInput permission =
                    page.getFirstByXPath("//*[@name='[USER:owner]']//input[@name='[hudson.model.Computer.Build]']");
            HtmlElement header =
                    page.getFirstByXPath("//*[@name='[USER:owner]']//div[contains(@class,'mas-card__header')]");
            if (header != null) header.click();
            assertTrue(permission.isChecked());
            permission.click();
            assertFalse(permission.isChecked());
            var error = contentError(page, MatrixPermissions.MESSAGE);
            assertFalse(error.hasAttribute("hidden"));
            assertSame(
                    page.querySelector("table.global-matrix-authorization-strategy-table, .mas-container"),
                    error.getPreviousSibling());
            HtmlButton save = page.getFirstByXPath("//button[@name='Submit']");
            assertSame(page, save.click());
            assertNotNull(j.jenkins.getNode("edit-node").getNodeProperty(AuthorizationMatrixNodeProperty.class));
            permission.click();
            client.waitForBackgroundJavaScript(1000);
            assertTrue(permission.isChecked());
            assertTrue(error.hasAttribute("hidden"));
            assertEquals(
                    0,
                    page.querySelectorAll(".node-property-field-error:not([hidden])")
                            .size());
            save.click();
            assertNull(policy.violation(j.jenkins.getNode("edit-node")));
        }
    }

    @Test
    void copyingAddsTheNewCreatorWithoutChangingTheSource() throws Exception {
        var source = new DumbSlave("copy-source", "agent-work", new JNLPLauncher());
        source.getNodeProperties()
                .add(new AuthorizationMatrixNodeProperty(
                        Map.of(Computer.BUILD, Set.of(PermissionEntry.user("owner")))));
        source.getNodeProperties().add(new JobRestrictionProperty(new RegexNameRestriction("approved-.*", false)));
        j.jenkins.addNode(source);
        try (var client = j.createWebClient().login("admin")) {
            var request = new WebRequest(client.createCrumbedUrl("computer/createItem"), HttpMethod.POST);
            request.setRequestParameters(List.of(
                    new NameValuePair("name", "copied"),
                    new NameValuePair("mode", "copy"),
                    new NameValuePair("from", "copy-source")));
            client.getPage(request);
        }
        var copied = j.jenkins.getNode("copied").getNodeProperty(AuthorizationMatrixNodeProperty.class);
        assertTrue(copied.hasExplicitPermission(PermissionEntry.user("admin"), Computer.DELETE));
        assertTrue(copied.hasExplicitPermission(PermissionEntry.user("owner"), Computer.BUILD));
        assertFalse(source.getNodeProperty(AuthorizationMatrixNodeProperty.class)
                .hasExplicitPermission(PermissionEntry.user("admin"), Computer.DELETE));
    }

    private static HtmlElement contentError(HtmlPage page, String message) {
        return page.<HtmlElement>getByXPath("//div[contains(@class,'node-property-field-error')]").stream()
                .filter(element -> element.getTextContent().equals(message))
                .findFirst()
                .orElseThrow();
    }

    private static JSONObject restriction(String type) {
        return new JSONObject()
                .element("stapler-class", type)
                .element("regexExpression", "approved-.*")
                .element("checkShortName", false);
    }

    private static JSONObject form(String name, Object restriction) {
        var properties = new JSONArray();
        properties.add(new JSONObject()
                .element("stapler-class", MATRIX)
                .element("data", new JSONObject())
                .element(
                        "inheritanceStrategy",
                        new JSONObject().element("stapler-class", InheritGlobalStrategy.class.getName())));
        properties.add(new JSONObject().element("stapler-class", JOBS).element("jobRestriction", restriction));
        return new JSONObject()
                .element("name", name)
                .element("numExecutors", "1")
                .element("remoteFS", "agent-work")
                .element("mode", "NORMAL")
                .element("labelString", "")
                .element("nodeDescription", "")
                .element("launcher", new JSONObject().element("stapler-class", JNLPLauncher.class.getName()))
                .element(
                        "retentionStrategy",
                        new JSONObject().element("stapler-class", "hudson.slaves.RetentionStrategy$Always"))
                .element("nodeProperties", properties);
    }

    private WebResponse post(String user, String path, JSONObject form) throws Exception {
        try (var client = j.createWebClient()) {
            if (user != null) client.login(user);
            client.getOptions().setJavaScriptEnabled(false);
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            client.getOptions().setPrintContentOnFailingStatusCode(false);
            var request = new WebRequest(client.createCrumbedUrl(path), HttpMethod.POST);
            request.setRequestParameters(List.of(new NameValuePair("json", form.toString())));
            return client.getPage(request).getWebResponse();
        }
    }
}
