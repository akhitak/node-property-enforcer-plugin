package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LabelSchedulingTest {
    @Test
    void sharedPoolUsesCompliantAgentsAndStillHonorsLabelExpressions(JenkinsRule j) throws Exception {
        j.jenkins.setNumExecutors(0);
        String pool = "policy-pool-" + UUID.randomUUID();
        var incomplete = j.createOnlineSlave();
        incomplete.setLabelString(pool + " windows");
        j.jenkins.updateNode(incomplete);
        var compliant = j.createOnlineSlave();
        compliant.setLabelString(pool + " linux");
        compliant.getNodeProperties().add(new EnvironmentVariablesNodeProperty());
        j.jenkins.updateNode(compliant);
        NodePropertyPolicy.get().replace(List.of(new Requirement(EnvironmentVariablesNodeProperty.class.getName())));
        var project = j.createFreeStyleProject();
        project.setAssignedLabel(j.jenkins.getLabel(pool));
        assertEquals(
                2, project.getAssignedLabel().getNodes().size(), "Both saved nodes must be in the shared label pool");
        assertSame(compliant, successful(j, project.scheduleBuild2(0)).getBuiltOn());

        compliant.getNodeProperties().remove(EnvironmentVariablesNodeProperty.class);
        var awaitingProperty = project.scheduleBuild2(0);
        assertWaiting(j, project, awaitingProperty);
        compliant.getNodeProperties().add(new EnvironmentVariablesNodeProperty());
        j.jenkins.getQueue().scheduleMaintenance();
        assertSame(compliant, successful(j, awaitingProperty).getBuiltOn());

        project.setAssignedLabel(j.jenkins.getLabel(pool + " && linux && !maintenance"));
        relabel(j, compliant.getNodeName(), pool + " linux maintenance");
        var awaitingLabel = project.scheduleBuild2(0);
        assertWaiting(j, project, awaitingLabel);
        relabel(j, compliant.getNodeName(), pool + " linux");
        j.jenkins.getQueue().scheduleMaintenance();
        assertEquals(compliant.getNodeName(), successful(j, awaitingLabel).getBuiltOnStr());
        assertTrue(incomplete.toComputer().isOnline());
    }

    private static void relabel(JenkinsRule j, String name, String labels) throws Exception {
        try (var client = j.createWebClient()) {
            var form = client.goTo("computer/" + name + "/configure").getFormByName("config");
            form.getInputByName("_.labelString").setValueAttribute(labels);
            j.submit(form);
        }
    }

    private static void assertWaiting(JenkinsRule j, FreeStyleProject project, QueueTaskFuture<FreeStyleBuild> future) {
        j.jenkins.getQueue().scheduleMaintenance();
        var start = future.getStartCondition();
        assertThrows(TimeoutException.class, () -> start.get(2, TimeUnit.SECONDS));
        assertNotNull(j.jenkins.getQueue().getItem(project));
        assertFalse(project.getLastBuild().isBuilding());
    }

    private static FreeStyleBuild successful(JenkinsRule j, QueueTaskFuture<FreeStyleBuild> future) throws Exception {
        FreeStyleBuild build;
        try {
            build = future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(
                    java.util.Arrays.stream(j.jenkins.getQueue().getItems())
                            .map(hudson.model.Queue.Item::getWhy)
                            .toList()
                            .toString(),
                    e);
        }
        j.assertBuildStatusSuccess(build);
        return build;
    }
}
