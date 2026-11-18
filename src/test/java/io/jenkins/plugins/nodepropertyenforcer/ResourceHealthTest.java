package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.JobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.JobRestrictionDescriptor;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.StartedByUserRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.AndJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.MultipleAndJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.NotJobRestriction;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleBuild;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.queue.SubTask;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ResourceHealthTest {
    @Test
    void repeatedQueueChecksHaveBoundedTimeAndAllocation(JenkinsRule j) throws Exception {
        var node = new DumbSlave("measured", "work", new JNLPLauncher());
        node.getNodeProperties().add(new JobRestrictionProperty(new RegexNameRestriction("approved-.*", false)));
        NodePropertyPolicy.get().replace(List.of(new Requirement(JobRestrictionProperty.class.getName())));
        var dispatcher = new PolicyQueueDispatcher();
        for (int i = 0; i < 200; i++) assertNull(dispatcher.canTake(node, (Queue.BuildableItem) null));
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long allocated = threads.getThreadAllocatedBytes(thread);
        long cpu = threads.getCurrentThreadCpuTime();
        long elapsed = System.nanoTime();
        assertTimeout(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < 2000; i++) assertNull(dispatcher.canTake(node, (Queue.BuildableItem) null));
        });
        elapsed = System.nanoTime() - elapsed;
        cpu = threads.getCurrentThreadCpuTime() - cpu;
        allocated = threads.getThreadAllocatedBytes(thread) - allocated;
        assertTrue(
                allocated < 128L * 1024 * 1024, "Temporary allocation per 2,000 queue checks must stay below 128 MiB");
        assertNull(threads.findDeadlockedThreads());
        System.out.printf(
                "Plugin health sample: 2000 queue checks, wall=%.3f ms, CPU=%.3f ms, allocation=%d bytes%n",
                elapsed / 1e6, cpu / 1e6, allocated);
    }

    @Test
    void branchingGraphsAndLargeSubmissionsHaveBoundedWork(JenkinsRule j) {
        var check = new JobRestrictions();
        JobRestriction tree = new RegexNameRestriction("approved-.*", false);
        // A small shared graph represents over a billion paths if traversed without a global budget.
        for (int i = 0; i < 30; i++) tree = new AndJobRestriction(tree, tree);
        var property = new JobRestrictionProperty(tree);
        assertTimeout(Duration.ofSeconds(3), () -> assertEquals(JobRestrictions.COMPLEXITY, check.check(property)));
        var leaf = type(RegexNameRestriction.class).element("regexExpression", "approved-.*");
        var group =
                type(MultipleAndJobRestriction.class).element("restrictions", java.util.Collections.nCopies(100, leaf));
        var input = new JSONObject()
                .element(
                        "jobRestriction",
                        type(MultipleAndJobRestriction.class)
                                .element("restrictions", java.util.Collections.nCopies(10, group)));
        assertTimeout(
                Duration.ofSeconds(3),
                () -> assertEquals(JobRestrictions.COMPLEXITY, check.checkSubmitted(input, false)));
        var cycle = mock(NotJobRestriction.class);
        when(cycle.getRestriction()).thenReturn(cycle);
        assertTimeout(
                Duration.ofSeconds(3),
                () -> assertEquals(JobRestrictions.LIMIT, check.check(new JobRestrictionProperty(cycle))));
        assertNotNull(check.check(new JobRestrictionProperty(null)));
        var missing = mock(StartedByUserRestriction.class);
        when(missing.getUsersList()).thenReturn(null);
        assertEquals("Add at least one user ID.", check.check(new JobRestrictionProperty(missing)));
        when(missing.getUsersList()).thenReturn(java.util.Collections.singletonList(null));
        assertEquals(JobRestrictions.USER, check.check(new JobRestrictionProperty(missing)));
        assertNotNull(
                check.checkSubmitted(new JSONObject().element("jobRestriction", JSONObject.fromObject("null")), false));
        assertNotNull(check.checkSubmitted(
                new JSONObject()
                        .element(
                                "jobRestriction",
                                type(StartedByUserRestriction.class)
                                        .element("usersList", List.of(JSONObject.fromObject("null")))),
                false));
        assertNull(check.check(new JobRestrictionProperty(new ExternalRestriction())));
        assertNull(check.checkSubmitted(
                new JSONObject().element("jobRestriction", type(ExternalRestriction.class)), false));
    }

    @Test
    void historyIsBoundedDeduplicatedAndDoesNotCreateThreads(JenkinsRule j) throws Exception {
        var tracker = new BuildAgents.Tracker();
        var run = trackedRun();
        var executor = executor(run, "agent-0");
        var task = mock(Queue.Task.class);
        var beans = java.lang.management.ManagementFactory.getThreadMXBean();
        var before = Thread.getAllStackTraces().keySet();
        for (int i = 0; i < 300; i++) {
            when(executor.getOwner().getNode().getNodeName()).thenReturn("agent-" + i);
            tracker.taskStarted(executor, task);
            tracker.taskStarted(executor, task);
        }
        var history = run.getAction(BuildAgents.class);
        assertEquals(256, history.getNames().size());
        assertEquals("agent-0", history.getNames().getFirst());
        assertFalse(history.getNames().contains("agent-256"));
        var snapshot = history.getNames();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("mutate"));
        verify(run).addAction(any(BuildAgents.class));
        verify(run, times(256)).save();
        assertNull(beans.findDeadlockedThreads());
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .filter(t -> !before.contains(t))
                .noneMatch(t -> java.util.Arrays.stream(t.getStackTrace())
                        .anyMatch(
                                frame -> frame.getClassName().startsWith("io.jenkins.plugins.nodepropertyenforcer"))));
        var failure = trackedRun();
        doThrow(new IOException("simulated history write failure"))
                .when(failure)
                .save();
        assertDoesNotThrow(() -> tracker.taskStarted(executor(failure, "write-failure"), task));
        assertEquals(
                List.of("write-failure"), failure.getAction(BuildAgents.class).getNames());
    }

    @Test
    void independentBuildsAreNotSerializedBehindSlowHistoryStorage(JenkinsRule j) throws Exception {
        var tracker = new BuildAgents.Tracker();
        var slow = trackedRun();
        var fast = trackedRun();
        var saving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    saving.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return null;
                })
                .when(slow)
                .save();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var first = pool.submit(() -> tracker.taskStarted(executor(slow, "slow"), null));
            assertTrue(saving.await(3, TimeUnit.SECONDS));
            var second = pool.submit(() -> tracker.taskStarted(executor(fast, "fast"), null));
            second.get(2, TimeUnit.SECONDS);
            assertEquals(List.of("fast"), fast.getAction(BuildAgents.class).getNames());
            var sameBuild = pool.submit(() -> tracker.taskStarted(executor(slow, "another"), null));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> sameBuild.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
            sameBuild.get(3, TimeUnit.SECONDS);
            assertEquals(
                    List.of("slow", "another"),
                    slow.getAction(BuildAgents.class).getNames());
            var updates = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 40; i++) {
                int id = i % 10;
                updates.add(pool.submit(() -> tracker.taskStarted(executor(fast, "parallel-" + id), null)));
            }
            for (var update : updates) update.get(3, TimeUnit.SECONDS);
            assertEquals(11, fast.getAction(BuildAgents.class).getNames().size());
            verify(fast).addAction(any(BuildAgents.class));
            verify(fast, times(11)).save();
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nonBuildExecutablesAndMissingNodesDoNotRecordHistory(JenkinsRule j) {
        var run = trackedRun();
        assertSame(run, BuildAgents.Tracker.owner(run));
        assertNull(BuildAgents.Tracker.owner(null));
        var executable = mock(Queue.Executable.class);
        var task = mock(SubTask.class);
        when(executable.getParent()).thenReturn(task);
        assertNull(BuildAgents.Tracker.owner(executable));
        when(task.getOwnerExecutable()).thenReturn(run);
        assertSame(run, BuildAgents.Tracker.owner(executable));
        var tracker = new BuildAgents.Tracker();
        var missing = executor(null, "unused");
        tracker.taskStarted(missing, null);
        when(missing.getCurrentExecutable()).thenReturn(run);
        when(missing.getOwner().getNode()).thenReturn(null);
        tracker.taskStarted(missing, null);
        verifyNoInteractions(run);
    }

    static FreeStyleBuild trackedRun() {
        var run = mock(FreeStyleBuild.class);
        var history = new AtomicReference<BuildAgents>();
        when(run.getAction(BuildAgents.class)).thenAnswer(call -> history.get());
        doAnswer(call -> {
                    history.set(call.getArgument(0));
                    return null;
                })
                .when(run)
                .addAction(any(BuildAgents.class));
        return run;
    }

    static Executor executor(Queue.Executable run, String name) {
        var executor = mock(Executor.class);
        var computer = mock(Computer.class);
        var node = mock(Node.class);
        when(executor.getCurrentExecutable()).thenReturn(run);
        when(executor.getOwner()).thenReturn(computer);
        when(computer.getNode()).thenReturn(node);
        when(node.getNodeName()).thenReturn(name);
        return executor;
    }

    private static JSONObject type(Class<?> type) {
        return new JSONObject().element("stapler-class", type.getName());
    }

    public static final class ExternalRestriction extends JobRestriction {
        @Override
        public boolean canTake(Queue.BuildableItem item) {
            return false;
        }

        @Override
        public boolean canTake(Run run) {
            return false;
        }

        @TestExtension("branchingGraphsAndLargeSubmissionsHaveBoundedWork")
        public static final class DescriptorImpl extends JobRestrictionDescriptor {
            @Override
            public String getDisplayName() {
                return "External test restriction";
            }
        }
    }
}
