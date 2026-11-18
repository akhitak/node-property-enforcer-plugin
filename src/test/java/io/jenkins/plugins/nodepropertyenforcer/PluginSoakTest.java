package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import hudson.slaves.DumbSlave;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Opt-in, rate-limited endurance test in a disposable controller; never connects to an existing home. */
@WithJenkins
@EnabledIfSystemProperty(named = "policy.soak", matches = "true")
class PluginSoakTest {
    private static final long MIB = 1024L * 1024;
    private final List<Long> latencies = new ArrayList<>();
    private final List<JSONObject> samples = new ArrayList<>();
    private final Path output = Path.of("target", "soak");
    private long checks;
    private int builds;
    private long baseHeap;
    private int baseThreads;
    private long start;
    private long cpuStart;
    private String sessionCookie;
    private URI base;

    @Test
    @SuppressWarnings("java:S2925") // Rate limiting and explicit GC settling are part of this opt-in workload.
    void sustainedSchedulingAndHttpWorkload(JenkinsRule j) throws Exception {
        int seconds = Integer.getInteger("policy.soak.seconds", 3600);
        int warmup = Integer.getInteger("policy.soak.warmupSeconds", 60);
        assertTrue(seconds >= 30 && seconds <= 86400);
        assertTrue(warmup >= 5 && warmup <= 600);
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("PASS"));
        Files.deleteIfExists(output.resolve("samples.json"));
        Files.writeString(
                output.resolve("metadata.json"),
                new JSONObject()
                        .element("startedAt", java.time.Instant.now().toString())
                        .element("measurementSeconds", seconds)
                        .element("warmupSeconds", warmup)
                        .element("javaVersion", System.getProperty("java.version"))
                        .element("jenkinsVersion", Jenkins.VERSION)
                        .element("maxHeapBytes", Runtime.getRuntime().maxMemory())
                        .element(
                                "jobRestrictionsVersion",
                                j.jenkins
                                        .getPluginManager()
                                        .getPlugin("job-restrictions")
                                        .getVersion())
                        .element(
                                "matrixAuthVersion",
                                j.jenkins
                                        .getPluginManager()
                                        .getPlugin("matrix-auth")
                                        .getVersion())
                        .toString(2));
        j.jenkins.setNumExecutors(0);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        var strategy = new ProjectMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("soak"));
        j.jenkins.setAuthorizationStrategy(strategy);
        try (var login = j.createWebClient().login("soak")) {
            sessionCookie = login.getCookieManager().getCookies().stream()
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                    .collect(java.util.stream.Collectors.joining("; "));
        }
        base = j.getURL().toURI();
        Files.writeString(output.resolve("url.txt"), base.toString());
        var nodes = new ArrayList<DumbSlave>();
        var blocked = new ArrayList<FreeStyleProject>();
        for (int mask = 0; mask < 4; mask++) {
            var node = j.createOnlineSlave();
            node.setLabelString("soak-pool");
            if ((mask & 1) != 0) {
                var permissions = new AuthorizationMatrixNodeProperty(Map.of());
                MatrixPermissions.nodePermissions().forEach(p -> permissions.add(p, PermissionEntry.user("soak")));
                node.getNodeProperties().add(permissions);
            }
            if ((mask & 2) != 0) {
                node.getNodeProperties()
                        .add(new JobRestrictionProperty(new RegexNameRestriction("soak-approved-.*", false)));
            }
            j.jenkins.updateNode(node);
            nodes.add(node);
            if (mask != 3) {
                var job = j.createFreeStyleProject("soak-approved-missing-" + mask);
                job.setAssignedNode(node);
                blocked.add(job);
            }
        }
        NodePropertyPolicy.get()
                .replace(List.of(
                        new Requirement(AuthorizationMatrixNodeProperty.class.getName()),
                        new Requirement(JobRestrictionProperty.class.getName())));
        var mismatch = j.createFreeStyleProject("soak-denied-pattern");
        mismatch.setAssignedNode(nodes.getLast());
        blocked.add(mismatch);
        blocked.forEach(job -> assertNotNull(job.scheduleBuild2(0)));
        var direct = j.createFreeStyleProject("soak-approved-direct");
        direct.setAssignedNode(nodes.getLast());
        var shared = j.createFreeStyleProject("soak-approved-shared");
        shared.setAssignedLabel(j.jenkins.getLabel("soak-pool"));
        var compound = j.createFreeStyleProject("soak-approved-compound");
        compound.setAssignedLabel(
                j.jenkins.getLabel("soak-pool && !" + nodes.getFirst().getNodeName()));
        var runnable = List.of(direct, shared, compound);
        for (var job : runnable) job.getBuildersList().add(new SleepBuilder(1500));
        var dispatcher = new PolicyQueueDispatcher();
        try (var http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            long warmEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(warmup);
            while (System.nanoTime() < warmEnd) {
                exercise(j, http, nodes, blocked, dispatcher);
                Thread.sleep(1000);
            }
            for (var job : runnable) build(j, http, job, nodes.getLast());
            System.gc();
            Thread.sleep(500);
            baseHeap = heap();
            baseThreads = ManagementFactory.getThreadMXBean().getThreadCount();
            checks = 0;
            builds = 0;
            latencies.clear();
            start = System.nanoTime();
            cpuStart = cpu();
            sample(false);
            int round = 0;
            long nextBuild = start;
            long nextSample = start + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(seconds)) {
                long tick = System.nanoTime();
                exercise(j, http, nodes, blocked, dispatcher);
                if (tick >= nextBuild) {
                    build(j, http, runnable.get(round++ % runnable.size()), nodes.getLast());
                    nextBuild = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
                }
                if (tick >= nextSample) {
                    System.gc();
                    Thread.sleep(500);
                    sample(false);
                    nextSample = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                }
                long pause = 1000 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tick);
                if (pause > 0) Thread.sleep(pause);
            }
            System.gc();
            Thread.sleep(500);
            sample(true);
            assertTrue(heap() - baseHeap < 32 * MIB, "Post-GC retained heap grew by 32 MiB or more");
            assertTrue(
                    ManagementFactory.getThreadMXBean().getThreadCount() <= baseThreads + 16,
                    "Live thread count grew by more than 16 after warm-up");
            assertTrue(percentile(99) < 2000, "Plugin HTTP p99 exceeded 2 seconds");
            double cpuFraction = (double) (cpu() - cpuStart) / (System.nanoTime() - start);
            assertTrue(cpuFraction < 0.5, "Isolated controller used over half of one CPU core on average");
            assertTrue(builds >= seconds / 65, "Insufficient completed builds for the requested duration");
            assertEquals(4, j.jenkins.getQueue().getItems().length);
            for (var job : blocked) assertNull(job.getLastBuild(), "A restricted job started");
            for (var node : nodes) assertTrue(node.toComputer().isOnline());
            Files.writeString(output.resolve("PASS"), "All soak assertions passed.\n");
        } finally {
            for (var job : blocked) j.jenkins.getQueue().cancel(job);
        }
    }

    private void exercise(
            JenkinsRule j,
            HttpClient http,
            List<DumbSlave> nodes,
            List<FreeStyleProject> blocked,
            PolicyQueueDispatcher dispatcher)
            throws Exception {
        for (int repeat = 0; repeat < 50; repeat++) {
            for (int mask = 0; mask < 4; mask++) {
                var problem = dispatcher.canTake(nodes.get(mask), (Queue.BuildableItem) null);
                if (mask == 3) assertNull(problem);
                else assertNotNull(problem);
                checks++;
            }
        }
        var queue = j.jenkins.getQueue();
        String ids = blocked.stream()
                .map(job -> {
                    var item = queue.getItem(job);
                    assertNotNull(item);
                    return Long.toString(item.getId());
                })
                .collect(java.util.stream.Collectors.joining(","));
        JSONObject items = get(http, "descriptorByName/" + QueueMessages.class.getName() + "/status?ids=" + ids)
                .getJSONObject("items");
        // Queue admission is asynchronous at fixture startup; scheduling assertions apply after warm-up.
        if (start != 0) {
            assertEquals(4, items.size());
            for (int mask = 0; mask < 3; mask++) {
                String message = items.getJSONArray(
                                Long.toString(queue.getItem(blocked.get(mask)).getId()))
                        .getJSONObject(0)
                        .getString("text");
                assertEquals((mask & 1) == 0, message.contains("Enable node-based security"));
                assertEquals((mask & 2) == 0, message.contains("Restrict jobs execution at node"));
            }
            assertTrue(items.getJSONArray(
                            Long.toString(queue.getItem(blocked.getLast()).getId()))
                    .getJSONObject(0)
                    .getString("text")
                    .contains("does not match"));
        }
        for (int mask = 0; mask < 4; mask++) {
            var problems = get(http, nodes.get(mask).toComputer().getUrl() + "node-property-status/")
                    .getJSONArray("problems");
            assertEquals(mask == 3, problems.isEmpty());
        }
        assertNull(ManagementFactory.getThreadMXBean().findDeadlockedThreads());
    }

    private void build(JenkinsRule j, HttpClient http, FreeStyleProject job, DumbSlave node) throws Exception {
        var future = job.scheduleBuild2(0);
        assertNotNull(future);
        var run = future.getStartCondition().get(20, TimeUnit.SECONDS);
        var running = get(http, run.getUrl() + "node-property-status/");
        assertTrue(running.getBoolean("building"));
        assertTrue(running.getJSONArray("problems").isEmpty());
        j.assertBuildStatusSuccess(future.get(20, TimeUnit.SECONDS));
        assertSame(node, run.getBuiltOn());
        var finished = get(http, run.getUrl() + "node-property-status/");
        assertFalse(finished.getBoolean("building"));
        assertTrue(finished.getJSONArray("problems").isEmpty());
        assertEquals(
                List.of(node.getNodeName()), run.getAction(BuildAgents.class).getNames());
        run.delete();
        builds++;
    }

    private JSONObject get(HttpClient http, String path) throws Exception {
        long before = System.nanoTime();
        var response = http.send(
                HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Cookie", sessionCookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        latencies.add(System.nanoTime() - before);
        assertEquals(200, response.statusCode(), path);
        return JSONObject.fromObject(response.body()).getJSONObject("data");
    }

    private void sample(boolean finished) throws Exception {
        var measurement = new JSONObject()
                .element("elapsedSeconds", (System.nanoTime() - start) / 1e9)
                .element("heapAfterGcBytes", heap())
                .element("baselineHeapBytes", baseHeap)
                .element("liveThreads", ManagementFactory.getThreadMXBean().getThreadCount())
                .element("baselineThreads", baseThreads)
                .element("cpuSeconds", (cpu() - cpuStart) / 1e9)
                .element("queueChecks", checks)
                .element("completedBuilds", builds)
                .element("httpRequests", latencies.size())
                .element("httpP99Millis", percentile(99))
                .element("finished", finished);
        samples.add(measurement);
        Files.writeString(
                output.resolve("samples.json"),
                net.sf.json.JSONArray.fromObject(samples).toString(2));
        System.out.println("Plugin soak sample: " + measurement);
    }

    private double percentile(int percent) {
        if (latencies.isEmpty()) return 0;
        var sorted = latencies.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, sorted.size() * percent / 100)) / 1e6;
    }

    private static long heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long cpu() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }
}
