package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.JobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.*;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.JNLPLauncher;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.mockito.MockMakers;

@WithJenkins
class QueueMessagesTest {
    private static final String ENDPOINT = "descriptorByName/" + QueueMessages.class.getName() + "/status?ids=";

    private static Queue.BuildableItem item(Queue.Task task) {
        return new Queue.BuildableItem(new Queue.WaitingItem(Calendar.getInstance(), task, List.of()));
    }

    private static DumbSlave node(String name, JobRestriction rule) throws Exception {
        var node = spy(new DumbSlave(name, "work", new JNLPLauncher()));
        if (rule != null) node.getNodeProperties().add(new JobRestrictionProperty(rule));
        return node;
    }

    @Test
    void liveEndpointExplainsWaitingJobsWithoutChangingTheQueueAndRespectsAccess(JenkinsRule j) throws Exception {
        j.jenkins.setNumExecutors(0);
        var agent = j.createOnlineSlave();
        j.jenkins.addNode(new DumbSlave("unrelated-node", "work", new JNLPLauncher()));
        agent.getNodeProperties().add(new JobRestrictionProperty(new RegexNameRestriction("test", false)));
        var job = j.createFreeStyleProject("different-name");
        job.setAssignedNode(agent);
        var future = job.scheduleBuild2(0);
        assertThrows(TimeoutException.class, () -> future.getStartCondition().get(1, TimeUnit.SECONDS));
        var queued = j.jenkins.getQueue().getItem(job);
        assertInstanceOf(Queue.BuildableItem.class, queued);
        String original = queued.getWhy();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader", "outsider")
                .grant(hudson.model.Item.READ)
                .everywhere()
                .to("reader"));
        try (var client = j.createWebClient().login("admin")) {
            var result = JSONObject.fromObject(client.goTo(ENDPOINT + queued.getId(), "application/json")
                            .getWebResponse()
                            .getContentAsString())
                    .getJSONObject("data");
            var message = result.getJSONObject("items")
                    .getJSONArray(Long.toString(queued.getId()))
                    .getJSONObject(0);
            assertTrue(message.getString("text").contains("does not match the required job-name pattern “test”"));
            assertTrue(message.getString("text").contains("different-name"));
            assertFalse(result.toString().contains("unrelated-node"));
            assertTrue(message.getString("url").endsWith("/configure"));
            assertEquals(original, queued.getWhy(), "Presentation must not replace Jenkins' scheduling state");
            assertTrue(agent.toComputer().isOnline());
            assertFalse(future.isDone());
            assertTrue(client.goTo(job.getUrl())
                    .getWebResponse()
                    .getContentAsString()
                    .contains("node-property-queue"));
        }
        try (var client = j.createWebClient().login("reader")) {
            String result = client.goTo(ENDPOINT + queued.getId(), "application/json")
                    .getWebResponse()
                    .getContentAsString();
            assertTrue(result.contains("does not meet this node's job-name rules"));
            assertFalse(result.contains("pattern"));
            assertFalse(result.contains("configure"));
        }
        try (var client = j.createWebClient().login("outsider")) {
            var result = JSONObject.fromObject(client.goTo(ENDPOINT + queued.getId(), "application/json")
                    .getWebResponse()
                    .getContentAsString());
            assertTrue(result.getJSONObject("data").getJSONObject("items").isEmpty());
        }
        try (var ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> new QueueMessages().doStatus(Long.toString(queued.getId())));
        }
        future.cancel(false);
    }

    @Test
    void endpointBoundsInputAndOmitsNonBuildablePendingAndNonJobTasks(JenkinsRule j) throws Exception {
        var endpoint = new QueueMessages();
        for (String invalid : new String[] {null, "", "-1", "abc", "9".repeat(19), "1,2,3,4,5,6,7,8,9"}) {
            assertThrows(RuntimeException.class, () -> endpoint.doStatus(invalid));
        }
        assertTrue(StatusLifecycleTest.json(endpoint.doStatus("999999999999999999"))
                .getJSONObject("items")
                .isEmpty());
        var policy = NodePropertyPolicy.get();
        policy.replace(List.of(), new PolicyBehavior(true, true, true, false, 15));
        assertFalse(endpoint.isEnabled());
        assertTrue(StatusLifecycleTest.json(endpoint.doStatus("1"))
                .getJSONObject("items")
                .isEmpty());
        policy.replace(List.of(), PolicyBehavior.defaults());
        assertTrue(endpoint.isEnabled());
        var job = j.createFreeStyleProject();
        var queue = mock(Queue.class);
        var pending = spy(item(job));
        when(pending.isPending()).thenReturn(true);
        when(queue.getItem(1)).thenReturn(new Queue.WaitingItem(Calendar.getInstance(), job, List.of()));
        when(queue.getItem(2)).thenReturn(pending);
        when(queue.getItem(3)).thenReturn(item(mock(Queue.Task.class)));
        var controller = mock(Jenkins.class);
        when(controller.getQueue()).thenReturn(queue);
        try (var global = mockStatic(Jenkins.class);
                var configuration = mockStatic(NodePropertyPolicy.class)) {
            global.when(Jenkins::get).thenReturn(controller);
            configuration.when(NodePropertyPolicy::get).thenReturn(policy);
            assertTrue(StatusLifecycleTest.json(endpoint.doStatus("1,2,3"))
                    .getJSONObject("items")
                    .isEmpty());
        }
    }

    @Test
    void messagesPreserveOtherSchedulingReasonsAndCoverPolicyAndLabelPools(JenkinsRule j) throws Exception {
        var policy = NodePropertyPolicy.get();
        var job = j.createFreeStyleProject("other-job");
        job.setAssignedLabel(null);
        var queued = spy(item(job));
        assertNull(queued.getAssignedLabel());
        assertTrue(QueueMessages.messages(queued, policy).isEmpty());
        var label = mock(Label.class);
        when(queued.getAssignedLabel()).thenReturn(label);
        when(label.getNodes()).thenReturn(Set.of());
        assertTrue(QueueMessages.messages(queued, policy).isEmpty());
        var many = new java.util.HashSet<Node>();
        for (int i = 0; i < 33; i++) many.add(mock(Node.class));
        when(label.getNodes()).thenReturn(many);
        assertTrue(QueueMessages.messages(queued, policy).isEmpty());
        var agent = node("agent", new RegexNameRestriction("test", false));
        when(label.getNodes()).thenReturn(Set.of(agent));
        assertTrue(QueueMessages.messages(queued, policy).isEmpty());
        var computer = mock(Computer.class);
        doReturn(computer).when(agent).toComputer();
        when(computer.isOffline()).thenReturn(true);
        assertTrue(QueueMessages.messages(queued, policy).isEmpty());
        when(computer.isOffline()).thenReturn(false);
        when(computer.getUrl()).thenReturn("computer/agent/");
        var request = mock(StaplerRequest2.class);
        when(request.getContextPath()).thenReturn("/jenkins");
        try (var stapler = mockStatic(Stapler.class)) {
            stapler.when(Stapler::getCurrentRequest2).thenReturn(request);
            assertEquals(
                    "/jenkins/computer/agent/configure",
                    QueueMessages.messages(queued, policy).getFirst().getString("url"));
            when(agent.hasPermission(Computer.CONFIGURE)).thenReturn(false);
            assertFalse(QueueMessages.messages(queued, policy).getFirst().containsKey("url"));
            policy.replace(List.of(new Requirement(EnvironmentVariablesNodeProperty.class.getName())));
            assertTrue(QueueMessages.messages(queued, policy)
                    .getFirst()
                    .getString("text")
                    .contains("Environment variables"));
            policy.replace(List.of(
                    new Requirement(EnvironmentVariablesNodeProperty.class.getName()),
                    new Requirement(hudson.tools.ToolLocationNodeProperty.class.getName())));
            String all = QueueMessages.messages(queued, policy).getFirst().getString("text");
            assertTrue(all.contains("Environment variables"));
            assertTrue(all.contains("Tool Locations"));
            assertTrue(all.contains("To run builds on this node"));
            agent.getNodeProperties().add(new EnvironmentVariablesNodeProperty());
            String remaining = QueueMessages.messages(queued, policy).getFirst().getString("text");
            assertFalse(remaining.contains("Environment variables"));
            assertTrue(remaining.contains("Tool Locations"));
            policy.replace(policy.getRequirements(), new PolicyBehavior(false, true, true, true, 30));
            assertTrue(QueueMessages.messages(queued, policy)
                    .getFirst()
                    .getString("text")
                    .contains("job-name rules"));
            agent.getNodeProperties().replace(new JobRestrictionProperty(new RegexNameRestriction(".*", false)));
            assertTrue(
                    QueueMessages.messages(queued, policy).isEmpty(),
                    "An eligible node must keep the native queue reason");
        }
    }

    @Test
    void providerMatchingAndBoundedNameTreesProduceTruthfulMessages(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("actual-name");
        job.setDisplayName("Friendly display name");
        var queued = item(job);
        var node = node("node", null);
        assertNull(QueueMessages.nameReason(node, queued));
        var mismatch = new RegexNameRestriction("allowed-.*", false);
        var match = new RegexNameRestriction("actual-name", true);
        for (var rule : List.of(
                mismatch,
                new NotJobRestriction(match),
                new AndJobRestriction(mismatch, match),
                new OrJobRestriction(mismatch, mismatch),
                new MultipleAndJobRestriction(new ArrayList<>(List.of(mismatch))),
                new MultipleOrJobRestriction(new ArrayList<>(List.of(mismatch))),
                new NotJobRestriction(new AndJobRestriction(match, match)))) {
            node.getNodeProperties().replace(new JobRestrictionProperty(rule));
            String message = QueueMessages.nameReason(node, queued);
            assertNotNull(message);
            assertTrue(message.contains("actual-name"));
            assertFalse(message.contains("Friendly display name"));
        }
        for (JobRestriction rule : new JobRestriction[] {
            null, match, new AnyJobRestriction(), new RegexNameRestriction("[", false), mock(JobRestriction.class)
        }) {
            node.getNodeProperties().replace(new JobRestrictionProperty(rule));
            assertNull(QueueMessages.nameReason(node, queued));
        }
        for (var type : List.of(
                RegexNameRestriction.class,
                AnyJobRestriction.class,
                AndJobRestriction.class,
                OrJobRestriction.class,
                NotJobRestriction.class,
                MultipleAndJobRestriction.class,
                MultipleOrJobRestriction.class)) {
            var custom = mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
            assertFalse(JobRestrictions.nameOnly(custom, 0, new int[] {128}));
            verify(custom, never()).canTake(any(Queue.BuildableItem.class));
        }
        var missingChild = spy(new AndJobRestriction(match, match));
        doReturn(null).when(missingChild).getFirst();
        assertFalse(JobRestrictions.nameOnly(missingChild, 0, new int[] {128}));
        var missingList = spy(new MultipleAndJobRestriction(null));
        doReturn(null).when(missingList).getRestrictions();
        assertFalse(JobRestrictions.nameOnly(missingList, 0, new int[] {128}));
        assertFalse(JobRestrictions.nameOnly(
                new MultipleOrJobRestriction(new ArrayList<>(java.util.Collections.nCopies(101, match))),
                0,
                new int[] {128}));
        assertFalse(JobRestrictions.nameOnly(match, 33, new int[] {128}));
        assertFalse(JobRestrictions.nameOnly(match, 0, new int[] {0}));
        JobRestriction tree = match;
        for (int i = 0; i < 20; i++) tree = new AndJobRestriction(tree, tree);
        assertFalse(JobRestrictions.nameOnly(tree, 0, new int[] {128}));
        assertEquals("x".repeat(160), QueueMessages.shortText("x".repeat(160)));
        assertEquals("x".repeat(157) + "…", QueueMessages.shortText("x".repeat(161)));
    }
}
