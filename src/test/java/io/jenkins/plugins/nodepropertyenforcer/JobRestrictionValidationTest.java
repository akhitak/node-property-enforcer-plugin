package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.JobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.StartedByMemberOfGroupRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.StartedByUserRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.AndJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.AnyJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.MultipleAndJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.MultipleOrJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.NotJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.OrJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.util.GroupSelector;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.util.UserSelector;
import hudson.model.Computer;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import io.jenkins.plugins.jobrestrictions.restrictions.job.JobClassNameRestriction;
import io.jenkins.plugins.jobrestrictions.util.ClassSelector;
import java.util.ArrayList;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class JobRestrictionValidationTest {
    private static final String JOBS = JobRestrictionProperty.class.getName();
    private static final RegexNameRestriction VALID = new RegexNameRestriction("approved-.*", false);
    private static final AnyJobRestriction ANY = new AnyJobRestriction();

    @Test
    void everyBuiltInTypeChecksItsRequiredContentAndNestedChildren(JenkinsRule j) throws Exception {
        var check = new JobRestrictions();
        var invalid = List.of(
                ANY,
                new AndJobRestriction(ANY, ANY),
                new AndJobRestriction(VALID, ANY),
                new OrJobRestriction(ANY, VALID),
                new NotJobRestriction(ANY),
                new NotJobRestriction(new OrJobRestriction(VALID, ANY)),
                new MultipleAndJobRestriction(new ArrayList<>()),
                new MultipleOrJobRestriction(new ArrayList<>()),
                new MultipleAndJobRestriction(new ArrayList<>(List.of(VALID, ANY))),
                new MultipleOrJobRestriction(new ArrayList<>(List.of(new NotJobRestriction(ANY)))),
                new RegexNameRestriction("", false),
                new RegexNameRestriction("   ", false),
                new RegexNameRestriction("[", false),
                new RegexNameRestriction("x".repeat(4097), false),
                new JobClassNameRestriction(List.of()),
                new JobClassNameRestriction(List.of(new ClassSelector(" "))),
                new JobClassNameRestriction(List.of(new ClassSelector("invalid class"))),
                new StartedByUserRestriction(List.of(), false, false, true),
                new StartedByUserRestriction(List.of(new UserSelector("")), false, false, false),
                new StartedByMemberOfGroupRestriction(List.of(), false),
                new StartedByMemberOfGroupRestriction(List.of(new GroupSelector(" ")), false));
        NodePropertyPolicy.get().replace(List.of(new Requirement(JOBS)));
        var node = new DumbSlave("tree-test", "agent-work", new JNLPLauncher());
        j.jenkins.addNode(node);
        for (JobRestriction restriction : invalid) {
            var property = new JobRestrictionProperty(restriction);
            node.getNodeProperties().replace(property);
            assertNotNull(check.check(property), restriction.getClass().getSimpleName());
            assertNotNull(new PolicyQueueDispatcher().canTake(node, (hudson.model.Queue.BuildableItem) null));
        }
        for (JobRestriction restriction : List.of(
                VALID,
                new AndJobRestriction(VALID, new NotJobRestriction(VALID)),
                new OrJobRestriction(VALID, VALID),
                new MultipleAndJobRestriction(new ArrayList<>(List.of(VALID))),
                new MultipleOrJobRestriction(new ArrayList<>(List.of(VALID, new NotJobRestriction(VALID)))),
                new JobClassNameRestriction(List.of(new ClassSelector("hudson.model.FreeStyleProject"))),
                new StartedByUserRestriction(List.of(new UserSelector("DOMAIN\\user")), true, false, false),
                new StartedByMemberOfGroupRestriction(List.of(new GroupSelector("builders")), true))) {
            var property = new JobRestrictionProperty(restriction);
            node.getNodeProperties().replace(property);
            assertNull(check.check(property), restriction.getClass().getSimpleName());
            assertNull(new PolicyQueueDispatcher().canTake(node, (hudson.model.Queue.BuildableItem) null));
        }
    }

    @Test
    void boundsAndMalformedValuesFailSafely(JenkinsRule j) {
        var check = new JobRestrictions();
        JobRestriction nested = VALID;
        for (int i = 0; i < 34; i++) nested = new NotJobRestriction(nested);
        assertEquals(JobRestrictions.LIMIT, check.check(new JobRestrictionProperty(nested)));
        var oversized = new ArrayList<JobRestriction>(java.util.Collections.nCopies(101, VALID));
        assertEquals(
                JobRestrictions.LIMIT,
                check.check(new JobRestrictionProperty(new MultipleAndJobRestriction(oversized))));
        for (JSONObject invalid : List.of(
                new JSONObject(),
                type(RegexNameRestriction.class).element("$class", AnyJobRestriction.class.getName()),
                new JSONObject().element("stapler-class", "unregistered.Restriction"),
                type(MultipleOrJobRestriction.class).element("restrictions", List.of("invalid row")))) {
            assertNotNull(check.checkSubmitted(new JSONObject().element("jobRestriction", invalid), false));
        }
        for (String invalid : List.of("", "   ", "NoPackage", "a..Class", "a.1Class", "a.Class.")) {
            assertEquals(RestrictionValues.CLASS_NAME, RestrictionValues.className(invalid));
        }
        assertEquals(RestrictionValues.CLASS_NAME, RestrictionValues.className(null));
        assertEquals(
                "Keep the job class name within 1024 characters.",
                RestrictionValues.className("a." + "x".repeat(1023)));
        assertNull(RestrictionValues.className("a." + "x".repeat(1022)));
        assertNull(RestrictionValues.className("example.Outer$Inner"));
        assertNull(RestrictionValues.className("example.\uD801\uDC00Class"));
    }

    @Test
    void directCreationAndEditingCannotBypassNestedOrFieldValidation(JenkinsRule j) throws Exception {
        NodePropertyPolicy.get().replace(List.of(new Requirement(JOBS)));
        var node = new DumbSlave("saved-node", "agent-work", new JNLPLauncher());
        node.getNodeProperties().add(new JobRestrictionProperty(VALID));
        j.jenkins.addNode(node);
        String original = java.nio.file.Files.readString(j.jenkins
                .getRootDir()
                .toPath()
                .resolve("nodes")
                .resolve(node.getNodeName())
                .resolve("config.xml"));
        var any = type(AnyJobRestriction.class);
        var valid = type(RegexNameRestriction.class).element("regexExpression", "approved-.*");
        var invalid = List.of(
                any,
                type(AndJobRestriction.class).element("first", any).element("second", any),
                type(OrJobRestriction.class).element("first", valid).element("second", any),
                type(NotJobRestriction.class).element("restriction", any),
                type(MultipleAndJobRestriction.class).element("restrictions", new JSONArray()),
                type(MultipleOrJobRestriction.class).element("restrictions", List.of(valid, any)),
                type(RegexNameRestriction.class).element("regexExpression", "["),
                type(RegexNameRestriction.class).element("regexExpression", " "),
                type(RegexNameRestriction.class).element("regexExpression", 42),
                type(JobClassNameRestriction.class)
                        .element("jobClasses", List.of(new JSONObject().element("selectedClass", ""))),
                type(StartedByUserRestriction.class).element("usersList", new JSONArray()),
                type(StartedByMemberOfGroupRestriction.class)
                        .element("groupList", new JSONObject().element("selectedGroupId", " ")),
                type(NotJobRestriction.class)
                        .element(
                                "restriction",
                                type(RegexNameRestriction.class)
                                        .element("$class", AnyJobRestriction.class.getName())
                                        .element("regexExpression", "approved-.*")));
        try (var client = j.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(false);
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            client.getOptions().setPrintContentOnFailingStatusCode(false);
            for (JSONObject restriction : invalid) {
                for (String route : List.of(
                        "computer/doCreateItem?name=incomplete&type=hudson.slaves.DumbSlave",
                        node.toComputer().getUrl() + "configSubmit")) {
                    var request = new WebRequest(client.createCrumbedUrl(route), HttpMethod.POST);
                    request.setRequestParameters(
                            List.of(new NameValuePair("json", form(restriction).toString())));
                    assertEquals(400, client.getPage(request).getWebResponse().getStatusCode(), restriction.toString());
                }
                assertNull(j.jenkins.getNode("incomplete"));
                assertEquals(
                        original,
                        java.nio.file.Files.readString(j.jenkins
                                .getRootDir()
                                .toPath()
                                .resolve("nodes")
                                .resolve(node.getNodeName())
                                .resolve("config.xml")));
            }
        }
        var check = new JobRestrictions();
        for (Class<?> kind : List.of(AndJobRestriction.class, OrJobRestriction.class)) {
            assertNull(check.checkSubmitted(
                    new JSONObject()
                            .element(
                                    "jobRestriction",
                                    type(kind).element("first", valid).element("second", valid)),
                    false));
        }
        assertNull(check.checkSubmitted(
                new JSONObject()
                        .element(
                                "jobRestriction",
                                type(MultipleAndJobRestriction.class).element("restrictions", valid)),
                false));
        assertNull(check.checkSubmitted(
                new JSONObject()
                        .element(
                                "jobRestriction",
                                type(StartedByUserRestriction.class)
                                        .element(
                                                "usersList",
                                                new JSONObject().element("selectedUserId", "sso@example.test"))),
                false));
    }

    @Test
    void nativeNestedErrorsStayAtTheirFieldsAndSaveAndApplyStayOnTheForm(JenkinsRule j) throws Exception {
        NodePropertyPolicy.get().replace(List.of(new Requirement(JOBS)));
        var node = new DumbSlave("nested-ui", "agent-work", new JNLPLauncher());
        node.getNodeProperties().add(new JobRestrictionProperty(new AndJobRestriction(ANY, ANY)));
        j.jenkins.addNode(node);
        String original = java.nio.file.Files.readString(j.jenkins
                .getRootDir()
                .toPath()
                .resolve("nodes")
                .resolve(node.getNodeName())
                .resolve("config.xml"));
        try (var client = j.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(true);
            HtmlPage page = client.goTo(node.toComputer().getUrl() + "configure");
            client.waitForBackgroundJavaScript(1000);
            List<HtmlSelect> selects = jobSelects(page);
            for (int i : List.of(1, 2)) {
                assertEquals("true", selects.get(i).getAttribute("aria-invalid"));
                assertEquals(
                        JobRestrictions.MESSAGE,
                        page.getElementById(selects.get(i).getAttribute("aria-describedby"))
                                .getTextContent());
            }
            HtmlButton save = page.getFirstByXPath("//button[@name='Submit']");
            HtmlButton apply = page.getFirstByXPath("//button[contains(@class,'apply-button')]");
            assertSame(page, save.click());
            assertSame(selects.get(1), page.getFocusedElement());
            apply.click();
            client.waitForBackgroundJavaScript(500);
            assertEquals(
                    original,
                    java.nio.file.Files.readString(j.jenkins
                            .getRootDir()
                            .toPath()
                            .resolve("nodes")
                            .resolve(node.getNodeName())
                            .resolve("config.xml")));
            assertEquals(
                    page.getUrl(), client.getCurrentWindow().getEnclosedPage().getUrl());
            select(selects.getFirst(), MultipleAndJobRestriction.class);
            client.waitForBackgroundJavaScript(1000);
            HtmlButton add = page.getFirstByXPath("//button[@suffix='restrictions']");
            add.click();
            client.waitForBackgroundJavaScript(500);
            HtmlButton unrestricted =
                    page.<HtmlButton>getByXPath("//button[contains(@class,'jenkins-dropdown__item')]").stream()
                            .filter(button -> button.getTextContent().trim().equals("No restriction (take any)"))
                            .findFirst()
                            .orElseThrow();
            unrestricted.click();
            client.waitForBackgroundJavaScript(1000);
            org.htmlunit.html.HtmlElement chunk = page.getFirstByXPath(
                    "//div[@name='restrictions' and @descriptorid='" + AnyJobRestriction.class.getName() + "']");
            assertNotNull(chunk, "The Save guard must not intercept Jenkins' add-entry menu.");
            assertSame(page, save.click());
            assertNotNull(
                    chunk.querySelector(".node-property-field-error:not([hidden])"),
                    "The error must appear inside the incomplete entry: " + chunk.asXml());
            HtmlButton remove = chunk.getFirstByXPath(".//button[contains(@class,'repeatable-delete')]");
            remove.click();
            client.waitForBackgroundJavaScript(500);
            assertTrue(page.asNormalizedText().contains(JobRestrictions.LIST));
            for (Class<?> kind : List.of(
                    MultipleAndJobRestriction.class,
                    MultipleOrJobRestriction.class,
                    JobClassNameRestriction.class,
                    StartedByUserRestriction.class,
                    StartedByMemberOfGroupRestriction.class,
                    NotJobRestriction.class,
                    RegexNameRestriction.class)) {
                select(selects.getFirst(), kind);
                client.waitForBackgroundJavaScript(1000);
                assertSame(page, save.click(), kind.getSimpleName());
                assertFalse(
                        page.querySelectorAll(".node-property-field-error:not([hidden])")
                                .isEmpty(),
                        kind.getSimpleName());
                assertEquals(
                        original,
                        java.nio.file.Files.readString(j.jenkins
                                .getRootDir()
                                .toPath()
                                .resolve("nodes")
                                .resolve(node.getNodeName())
                                .resolve("config.xml")));
            }
            var expression = page.getFormByName("config").getInputByName("_.regexExpression");
            expression.type("[");
            client.waitForBackgroundJavaScript(1200);
            assertSame(page, save.click());
            assertTrue(page.asNormalizedText().contains(RestrictionValues.INVALID_EXPRESSION));
            expression.setValue("");
            expression.type("approved-.*");
            client.waitForBackgroundJavaScript(1200);
            assertEquals("approved-.*", expression.getValue());
            assertEquals(
                    "",
                    page.querySelectorAll(".node-property-field-error:not([hidden])").stream()
                            .map(org.htmlunit.html.DomNode::getTextContent)
                            .collect(java.util.stream.Collectors.joining(" | ")));
            save.click();
            assertNull(NodePropertyPolicy.get().violation(j.jenkins.getNode("nested-ui")));
        }
    }

    @Test
    void validationEndpointRequiresReadPostAndCrumbAndSupportsNonAdminEditors(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new org.jvnet.hudson.test.MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ, Computer.CONFIGURE)
                .everywhere()
                .to("editor"));
        String endpoint =
                "descriptorByName/io.jenkins.plugins.nodepropertyenforcer.Requirement/validateRestrictionValue";
        try (var client = j.createWebClient().login("editor")) {
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            client.getOptions().setPrintContentOnFailingStatusCode(false);
            assertEquals(
                    405,
                    client.getPage(client.createCrumbedUrl(endpoint))
                            .getWebResponse()
                            .getStatusCode());
            var request = new WebRequest(client.createCrumbedUrl(endpoint), HttpMethod.POST);
            request.setRequestParameters(
                    List.of(new NameValuePair("kind", "expression"), new NameValuePair("value", "[")));
            var response = client.getPage(request).getWebResponse();
            assertEquals(200, response.getStatusCode());
            assertEquals(
                    RestrictionValues.INVALID_EXPRESSION,
                    JSONObject.fromObject(response.getContentAsString())
                            .getJSONObject("data")
                            .getString("message"));
            var noCrumb = new WebRequest(new java.net.URL(j.getURL(), endpoint), HttpMethod.POST);
            noCrumb.setRequestParameters(request.getRequestParameters());
            assertEquals(403, client.getPage(noCrumb).getWebResponse().getStatusCode());
        }
        try (var client = j.createWebClient()) {
            client.getOptions().setRedirectEnabled(false);
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            client.getOptions().setPrintContentOnFailingStatusCode(false);
            var request = new WebRequest(client.createCrumbedUrl(endpoint), HttpMethod.POST);
            var response = client.getPage(request).getWebResponse();
            assertEquals("text/html", response.getContentType());
            assertTrue(response.getContentAsString().contains("Sign in to Jenkins"));
        }
    }

    private static List<HtmlSelect> jobSelects(HtmlPage page) {
        return page.getByXPath(
                "//input[@name='com-synopsys-arc-jenkinsci-plugins-jobrestrictions-nodes-JobRestrictionProperty']/ancestor::div[contains(@class,'optionalBlock-container')]//select[contains(@class,'dropdownList')]");
    }

    private static void select(HtmlSelect select, Class<?> type) {
        int index = java.util.stream.IntStream.range(0, JobRestriction.all().size())
                .filter(i -> JobRestriction.all().get(i).clazz == type)
                .findFirst()
                .orElseThrow();
        select.setSelectedAttribute(Integer.toString(index), true);
    }

    private static JSONObject type(Class<?> kind) {
        return new JSONObject().element("stapler-class", kind.getName());
    }

    private static JSONObject form(JSONObject restriction) {
        return new JSONObject()
                .element("name", "saved-node")
                .element("numExecutors", "1")
                .element("remoteFS", "agent-work")
                .element("mode", "NORMAL")
                .element("labelString", "")
                .element("nodeDescription", "")
                .element("launcher", type(JNLPLauncher.class))
                .element("retentionStrategy", type(hudson.slaves.RetentionStrategy.Always.class))
                .element(
                        "nodeProperties",
                        List.of(type(JobRestrictionProperty.class).element("jobRestriction", restriction)));
    }
}
