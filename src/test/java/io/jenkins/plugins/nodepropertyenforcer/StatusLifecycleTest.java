package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import hudson.model.Computer;
import hudson.model.ComputerSet;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.JNLPLauncher;
import java.io.ByteArrayOutputStream;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

@WithJenkins
class StatusLifecycleTest {
    private static final String ENV = EnvironmentVariablesNodeProperty.class.getName();

    @Test
    void bannersAppearOnlyOnRelevantReadablePages(JenkinsRule j) throws Exception {
        var banner = new PolicyBanner();
        assertNull(banner.getFormPolicy());
        assertNull(banner.getStatusUrl());
        var request = mock(StaplerRequest2.class);
        when(request.getContextPath()).thenReturn("/jenkins");
        try (var stapler = mockStatic(Stapler.class)) {
            stapler.when(Stapler::getCurrentRequest2).thenReturn(request);
            assertNull(banner.getFormPolicy());
            assertNull(banner.getStatusUrl());
            var computers = mock(ComputerSet.class);
            when(request.findAncestorObject(ComputerSet.class)).thenReturn(computers);
            NodePropertyPolicy.get().replace(List.of(new Requirement(ENV)));
            assertEquals(
                    1,
                    JSONObject.fromObject(banner.getFormPolicy())
                            .getJSONArray("rules")
                            .size());
            var computer = mock(Computer.class);
            when(request.findAncestorObject(Computer.class)).thenReturn(computer);
            when(computer.getNode()).thenReturn(j.jenkins);
            assertNull(banner.getFormPolicy());
            when(computer.getNode()).thenReturn(new DumbSlave("agent", "work", new JNLPLauncher()));
            when(computer.getUrl()).thenReturn("computer/agent/");
            assertNotNull(banner.getFormPolicy());
            assertEquals("/jenkins/computer/agent/node-property-status/", banner.getStatusUrl());
            var run = mock(Run.class);
            when(run.getUrl()).thenReturn("job/project/1/");
            when(request.findAncestorObject(Run.class)).thenReturn(run);
            assertEquals("/jenkins/job/project/1/node-property-status/", banner.getStatusUrl());
            NodePropertyPolicy.get().replace(List.of(new Requirement("missing.Property", false)));
            var file = j.jenkins.getRootDir().toPath().resolve(NodePropertyPolicy.class.getName() + ".xml");
            java.nio.file.Files.writeString(
                    file,
                    java.nio.file.Files.readString(file)
                            .replace("<enabled>false</enabled>", "<enabled>true</enabled>"));
            NodePropertyPolicy.get().load();
            var rule = JSONObject.fromObject(banner.getFormPolicy())
                    .getJSONArray("rules")
                    .getJSONObject(0);
            assertEquals("", rule.getString("name"));
            assertEquals("Required option", rule.getString("label"));
            assertTrue(rule.getString("problem").contains("unavailable"));
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                    .grant(Jenkins.READ)
                    .everywhere()
                    .to("reader"));
            try (var ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
                assertNull(banner.getFormPolicy());
            }
        }
    }

    @Test
    void statusHandlesDeletedNodesWorkflowHistoryAndHiddenWarnings(JenkinsRule j) throws Exception {
        var policy = NodePropertyPolicy.get();
        policy.replace(List.of(new Requirement(ENV)));
        var computers = new PolicyStatus.Computers();
        var builds = new PolicyStatus.Builds();
        assertEquals(Computer.class, computers.type());
        assertEquals(Run.class, builds.type());
        var request = mock(StaplerRequest2.class);
        when(request.getContextPath()).thenReturn("/jenkins");
        try (var stapler = mockStatic(Stapler.class)) {
            stapler.when(Stapler::getCurrentRequest2).thenReturn(request);
            var computer = mock(Computer.class);
            var status = (PolicyStatus) computers.createFor(computer).iterator().next();
            assertTrue(json(status.doIndex()).getJSONArray("problems").isEmpty());
            var node = spy(new DumbSlave("existing", "work", new JNLPLauncher()));
            when(computer.getNode()).thenReturn(node);
            assertFalse(json(status.doIndex())
                    .getJSONArray("problems")
                    .getJSONObject(0)
                    .containsKey("url"));
            j.jenkins.addNode(node);
            assertEquals(
                    "/jenkins/computer/existing/configure",
                    json(status.doIndex())
                            .getJSONArray("problems")
                            .getJSONObject(0)
                            .getString("url"));
            doReturn(false).when(node).hasPermission(Computer.CONFIGURE);
            assertFalse(json(status.doIndex())
                    .getJSONArray("problems")
                    .getJSONObject(0)
                    .containsKey("url"));
            var run = mock(Run.class);
            doReturn(j.createFreeStyleProject()).when(run).getParent();
            var buildStatus = (PolicyStatus) builds.createFor(run).iterator().next();
            assertTrue(json(buildStatus.doIndex()).getJSONArray("problems").isEmpty());
            var tracked = ResourceHealthTest.trackedRun();
            var tracker = new BuildAgents.Tracker();
            tracker.taskStarted(ResourceHealthTest.executor(tracked, "existing"), null);
            tracker.taskStarted(ResourceHealthTest.executor(tracked, "deleted"), null);
            var history = tracked.getAction(BuildAgents.class);
            when(run.getAction(BuildAgents.class)).thenReturn(history);
            when(run.isBuilding()).thenReturn(true);
            var result = json(buildStatus.doIndex());
            assertTrue(result.getBoolean("building"));
            assertEquals(1, result.getJSONArray("problems").size());
            policy.replace(List.of(new Requirement(ENV)), new PolicyBehavior(true, true, false, false, 30));
            assertTrue(json(status.doIndex()).getJSONArray("problems").isEmpty());
            assertTrue(json(buildStatus.doIndex()).getJSONArray("problems").isEmpty());
            policy.replace(List.of(new Requirement(ENV)), PolicyBehavior.defaults());
            node.getNodeProperties().add(new EnvironmentVariablesNodeProperty());
            assertTrue(json(status.doIndex()).getJSONArray("problems").isEmpty());
        }
    }

    @Test
    void valueEndpointChecksKindsAndReturnsOnlyValidationData(JenkinsRule j) throws Exception {
        var descriptor = j.jenkins.getDescriptorByType(Requirement.DescriptorImpl.class);
        for (String kind : new String[] {null, "unknown"}) {
            assertEquals(
                    "Select a supported restriction field.",
                    json(descriptor.doValidateRestrictionValue("value", kind)).getString("message"));
        }
        assertEquals(
                "",
                json(descriptor.doValidateRestrictionValue("hudson.model.FreeStyleProject", "classes"))
                        .getString("message"));
        assertEquals(
                RestrictionValues.CLASS_NAME,
                json(descriptor.doValidateRestrictionValue(" ", "classes")).getString("message"));
    }

    static JSONObject json(HttpResponse result) throws Exception {
        var writer = new ByteArrayOutputStream();
        var output = mock(jakarta.servlet.ServletOutputStream.class);
        doAnswer(call -> {
                    writer.writeBytes(call.getArgument(0));
                    return null;
                })
                .when(output)
                .write(any(byte[].class));
        var response = mock(StaplerResponse2.class);
        when(response.getOutputStream()).thenReturn(output);
        result.generateResponse(mock(StaplerRequest2.class), response, null);
        return JSONObject.fromObject(writer.toString(java.nio.charset.StandardCharsets.UTF_8))
                .getJSONObject("data");
    }
}
