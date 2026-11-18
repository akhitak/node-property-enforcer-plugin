package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;

import hudson.slaves.EnvironmentVariablesNodeProperty;
import java.util.List;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FormGuidanceTest {
    @Test
    void errorsStayAtTheirCheckboxesAndSaveFocusesTheRemainingOption(JenkinsRule j) throws Exception {
        var agent = j.createSlave();
        NodePropertyPolicy.get()
                .replace(List.of(
                        new Requirement(EnvironmentVariablesNodeProperty.class.getName()),
                        new Requirement(
                                com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty.class
                                        .getName())));
        try (var client = j.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(true);
            HtmlPage page = client.goTo(agent.toComputer().getUrl() + "configure");
            client.waitForBackgroundJavaScript(1000);
            HtmlCheckBoxInput environment =
                    page.getFormByName("config").getInputByName("hudson-slaves-EnvironmentVariablesNodeProperty");
            HtmlCheckBoxInput jobs = page.getFormByName("config")
                    .getInputByName("com-synopsys-arc-jenkinsci-plugins-jobrestrictions-nodes-JobRestrictionProperty");
            HtmlElement error = page.getHtmlElementById(environment.getAttribute("aria-describedby"));
            assertEquals("true", environment.getAttribute("aria-invalid"));
            assertTrue(
                    ((HtmlElement) error.getParentNode()).getAttribute("class").contains("optional-block-start"));
            assertFalse(error.hasAttribute("hidden"));
            assertNull(page.querySelector(".node-property-form-notice"));
            environment.click();
            assertTrue(error.hasAttribute("hidden"));
            assertFalse(environment.hasAttribute("aria-invalid"));
            HtmlButton save = page.getFirstByXPath("//button[@name='Submit']");
            assertSame(page, save.click());
            assertSame(jobs, page.getFocusedElement());
            assertTrue(agent.getNodeProperties().isEmpty());
        }
    }

    @Test
    void editFormCarriesRequiredOptionsAndRespectsEditSwitch(JenkinsRule j) throws Exception {
        var agent = j.createSlave();
        var policy = NodePropertyPolicy.get();
        policy.replace(List.of(new Requirement(EnvironmentVariablesNodeProperty.class.getName())));
        try (var client = j.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(false);
            var metadata = metadata(client.goTo(agent.toComputer().getUrl() + "configure"));
            assertTrue(metadata.getBoolean("checkEdits"));
            assertEquals(
                    "hudson-slaves-EnvironmentVariablesNodeProperty",
                    metadata.getJSONArray("rules").getJSONObject(0).getString("name"));
            policy.replace(policy.getRequirements(), new PolicyBehavior(true, false, true, true, 30));
            assertFalse(metadata(client.goTo(agent.toComputer().getUrl() + "configure"))
                    .getBoolean("checkEdits"));
        }
    }

    @Test
    void creationDetailsReceiveGuidanceWithoutCreatingAgent(JenkinsRule j) throws Exception {
        NodePropertyPolicy.get().replace(List.of(new Requirement(EnvironmentVariablesNodeProperty.class.getName())));
        try (var client = j.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(false);
            var request = new WebRequest(client.createCrumbedUrl("computer/createItem"), HttpMethod.POST);
            request.setRequestParameters(List.of(
                    new NameValuePair("name", "new-agent"), new NameValuePair("mode", "hudson.slaves.DumbSlave")));
            HtmlPage page = client.getPage(request);
            assertEquals(200, page.getWebResponse().getStatusCode());
            assertEquals(1, metadata(page).getJSONArray("rules").size());
            assertTrue(page.getFormByName("config").getActionAttribute().contains("doCreateItem"));
            assertNull(j.jenkins.getNode("new-agent"));
        }
    }

    @Test
    void unrelatedAndBuiltInPagesHaveNoFormPolicy(JenkinsRule j) throws Exception {
        NodePropertyPolicy.get().replace(List.of(new Requirement(EnvironmentVariablesNodeProperty.class.getName())));
        var job = j.createFreeStyleProject();
        try (var client = j.createWebClient()) {
            client.getOptions().setJavaScriptEnabled(false);
            assertNull(client.goTo(job.getUrl() + "configure").getElementById("node-property-form-policy"));
            assertNull(client.goTo("computer/(built-in)/configure").getElementById("node-property-form-policy"));
        }
    }

    private static JSONObject metadata(HtmlPage page) {
        var element = page.getElementById("node-property-form-policy");
        assertNotNull(element);
        return JSONObject.fromObject(element.getAttribute("data-policy"));
    }
}
