package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Result;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import java.util.List;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ExistingAgentsTest {
    @Test
    void runningBuildContinuesWhileNextBuildWaitsAndCompletedConsoleRetainsWarning(JenkinsRule j) throws Exception {
        var agent = j.createOnlineSlave();
        var project = j.createFreeStyleProject("live-policy");
        project.setAssignedNode(agent);
        project.getBuildersList().add(new SleepBuilder(12_000));
        var first = project.scheduleBuild2(0).waitForStart();
        var policy = NodePropertyPolicy.get();
        policy.replace(List.of(new Requirement(EnvironmentVariablesNodeProperty.class.getName())));
        var next = project.scheduleBuild2(0);
        assertTrue(first.isBuilding());
        assertFalse(agent.toComputer().isOffline());
        try (var client = j.createWebClient()) {
            JSONObject status = JSONObject.fromObject(
                            client.goTo(first.getUrl() + "node-property-status/", "application/json")
                                    .getWebResponse()
                                    .getContentAsString())
                    .getJSONObject("data");
            assertTrue(status.getBoolean("building"));
            assertEquals(1, status.getJSONArray("problems").size());
            client.getOptions().setJavaScriptEnabled(false);
            var html = client.goTo(first.getUrl() + "console");
            assertNotNull(html.getElementById("node-property-banner"));
        }
        j.assertBuildStatusSuccess(j.waitForCompletion(first));
        assertFalse(next.isDone());
        assertNotNull(j.jenkins.getQueue().getItem(project));
        assertEquals(Result.SUCCESS, first.getResult());
        try (var client = j.createWebClient()) {
            JSONObject status = JSONObject.fromObject(
                            client.goTo(first.getUrl() + "node-property-status/", "application/json")
                                    .getWebResponse()
                                    .getContentAsString())
                    .getJSONObject("data");
            assertFalse(status.getBoolean("building"));
            assertEquals(1, status.getJSONArray("problems").size());
        }
        agent.getNodeProperties().add(new EnvironmentVariablesNodeProperty());
        j.jenkins.getQueue().scheduleMaintenance();
        j.assertBuildStatusSuccess(next.get(60, java.util.concurrent.TimeUnit.SECONDS));
        try (var client = j.createWebClient()) {
            JSONObject status = JSONObject.fromObject(
                            client.goTo(first.getUrl() + "node-property-status/", "application/json")
                                    .getWebResponse()
                                    .getContentAsString())
                    .getJSONObject("data");
            assertTrue(status.getJSONArray("problems").isEmpty());
        }
    }

    @Test
    void warningOnlyAndDisabledRulesDoNotBlockAndOptionsSurviveRestart(JenkinsRule j) throws Exception {
        var policy = NodePropertyPolicy.get();
        var agent = j.createSlave();
        var warningOnly = new PolicyBehavior(false, false, false, false, 15);
        policy.replace(List.of(new Requirement(EnvironmentVariablesNodeProperty.class.getName())), warningOnly);
        assertNotNull(policy.violation(agent));
        assertNull(new PolicyQueueDispatcher().canTake(agent, (hudson.model.Queue.BuildableItem) null));
        policy.load();
        assertFalse(policy.getBehavior().isBlockBuilds());
        assertFalse(policy.getBehavior().isEnforceEdits());
        assertEquals(15, policy.getBehavior().getRefreshSeconds());
        policy.replace(List.of(new Requirement(EnvironmentVariablesNodeProperty.class.getName(), false)));
        assertTrue(policy.enabledRequirements().isEmpty());
        assertNull(policy.violation(agent));
        assertThrows(IllegalArgumentException.class, () -> new PolicyBehavior(true, true, true, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new PolicyBehavior(true, true, true, true, 301));
    }
}
