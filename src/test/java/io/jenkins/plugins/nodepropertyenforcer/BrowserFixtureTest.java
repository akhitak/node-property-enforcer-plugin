package io.jenkins.plugins.nodepropertyenforcer;

import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty;
import hudson.tasks.BatchFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Opt-in disposable fixture for manual browser validation, using a local stdio agent. */
@WithJenkins
@EnabledIfSystemProperty(named = "policy.browserFixture", matches = "true")
class BrowserFixtureTest {
    @Test
    @SuppressWarnings("java:S2925") // Intentional opt-in manual fixture lifetime, not a timing-dependent assertion.
    void browserSession(JenkinsRule j) throws Exception {
        var agent = j.createOnlineSlave();
        org.junit.jupiter.api.Assertions.assertTrue(agent.toComputer().isOnline());
        agent.setLabelString("ui-shared-pool");
        j.jenkins.updateNode(agent);
        var compliantAgent = j.createOnlineSlave();
        compliantAgent.setLabelString("ui-shared-pool");
        compliantAgent
                .getNodeProperties()
                .add(new JobRestrictionProperty(
                        new com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction(
                                "policy-.*", false)));
        org.junit.jupiter.api.Assertions.assertTrue(compliantAgent.toComputer().isOnline());
        j.jenkins.updateNode(compliantAgent);
        var labelJob = j.createFreeStyleProject("policy-label-demo");
        labelJob.setAssignedLabel(j.jenkins.getLabel("ui-shared-pool"));
        labelJob.getBuildersList().add(new BatchFile("@echo off\r\necho Selected agent: %NODE_NAME%\r\n"));
        labelJob.save();
        var job = j.createFreeStyleProject("policy-console-demo");
        job.setAssignedNode(agent);
        job.getBuildersList()
                .add(
                        new BatchFile(
                                "@echo off\r\necho Build is running normally\r\nping -n 91 127.0.0.1 >nul\r\necho Build completed normally\r\n"));
        job.save();
        var directory = Path.of(".work");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("browser-fixture-url.txt"), j.getURL().toString());
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(12);
        while (!Files.exists(directory.resolve("stop-browser-fixture")) && System.nanoTime() < end) {
            Thread.sleep(500);
        }
    }
}
