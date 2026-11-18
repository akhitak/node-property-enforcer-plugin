package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

@WithJenkins
class BoundaryCoverageTest {
    private static final String ENV = EnvironmentVariablesNodeProperty.class.getName();

    @Test
    void inMemoryJsonNullSentinelsNeverBecomeUsablePropertiesOrValues(JenkinsRule j) throws Exception {
        // JSON-lib normalizes these sentinels during serialization. Keep them in memory to
        // test the defensive boundaries themselves, without removing their null checks.
        var sentinel = JSONObject.fromObject("null");
        var type = JobRestrictions.class.getDeclaredMethod("type", JSONObject.class);
        type.setAccessible(true);
        assertNull(type.invoke(null, sentinel));
        var value = JobRestrictions.class.getDeclaredMethod("string", JSONObject.class, String.class);
        value.setAccessible(true);
        assertNull(value.invoke(null, null, "field"));
        var bag = mock(JSONObject.class);
        var descriptor = NodePropertyPolicy.descriptor(ENV);
        when(bag.optJSONObject(descriptor.getJsonSafeClassName())).thenReturn(sentinel);
        var property =
                SubmittedProperties.class.getDeclaredMethod("property", JSONObject.class, NodePropertyDescriptor.class);
        property.setAccessible(true);
        assertNull(property.invoke(null, bag, descriptor));
        var add = SubmittedProperties.class.getDeclaredMethod("addProperty", Map.class, Object.class);
        add.setAccessible(true);
        var properties = new java.util.HashMap<String, JSONObject>();
        add.invoke(null, properties, sentinel);
        assertTrue(properties.isEmpty());
        var entry = java.util.Arrays.stream(JobRestrictions.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("entryProblem"))
                .findFirst()
                .orElseThrow();
        var budgetType = entry.getParameterTypes()[3];
        var constructor = budgetType.getDeclaredConstructor();
        constructor.setAccessible(true);
        entry.setAccessible(true);
        java.util.function.Function<JSONObject, String> validator =
                row -> fail("A JSON null must not reach the row validator");
        assertEquals("Missing row", entry.invoke(null, sentinel, "Missing row", validator, constructor.newInstance()));
        var missingList = new JSONObject()
                .element(
                        "stapler-class",
                        com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.StartedByUserRestriction
                                .class
                                .getName());
        assertEquals(
                "Add at least one user ID.",
                new JobRestrictions().checkSubmitted(new JSONObject().element("jobRestriction", missingList), false));
    }

    @Test
    void submittedParametersAndPropertyShapesAreUnambiguous(JenkinsRule j) throws Exception {
        var request = mock(HttpServletRequest.class);
        assertNull(SubmittedProperties.parameter(request, "type"));
        when(request.getParameterValues("type")).thenReturn(new String[0], new String[] {"a", "b"});
        assertThrows(IOException.class, () -> SubmittedProperties.parameter(request, "type"));
        assertThrows(IOException.class, () -> SubmittedProperties.parameter(request, "type"));
        for (String type : new String[] {null, "missing.Type", ENV, DumbSlave.class.getName()}) {
            when(request.getParameterValues("type")).thenReturn(type == null ? null : new String[] {type});
            if (DumbSlave.class.getName().equals(type))
                assertEquals(DumbSlave.class, SubmittedProperties.type(request));
            else assertThrows(IOException.class, () -> SubmittedProperties.type(request));
        }
        when(request.getPathInfo()).thenReturn("/computer/agent/configSubmit");
        var descriptor = NodePropertyPolicy.descriptor(ENV);
        for (JSONObject property : List.of(
                new JSONObject(),
                new JSONObject().element("stapler-class", "wrong"),
                new JSONObject().element("$class", "wrong"),
                JSONObject.fromObject("null"))) {
            var bag = new JSONObject()
                    .element("stapler-class-bag", true)
                    .element(descriptor.getJsonSafeClassName(), property);
            when(request.getParameterValues("json")).thenReturn(new String[] {
                new JSONObject().element("nodeProperties", bag).toString()
            });
            String problem = SubmittedProperties.check(request, List.of(new Requirement(ENV)), DumbSlave.class);
            assertEquals(property.isEmpty() && !property.isNullObject(), problem == null);
        }
        for (String properties : List.of(
                "[]",
                "[null]",
                "[false]",
                "[{\"stapler-class\":\"" + ENV + "\",\"$class\":\"wrong\"}]",
                "[{\"stapler-class\":\"" + ENV + "\"},{\"stapler-class\":\"" + ENV + "\"}]")) {
            when(request.getParameterValues("json"))
                    .thenReturn(new String[] {"{\"nodeProperties\":" + properties + "}"});
            if (properties.contains("stapler-class")) {
                assertThrows(
                        IOException.class,
                        () -> SubmittedProperties.check(request, List.of(new Requirement(ENV)), DumbSlave.class));
            } else assertNotNull(SubmittedProperties.check(request, List.of(new Requirement(ENV)), DumbSlave.class));
        }
        String limited = LimitedProperty.class.getName();
        when(request.getParameterValues("json"))
                .thenReturn(new String[] {"{\"nodeProperties\":[{\"stapler-class\":\"" + limited + "\"}]}"});
        assertNotNull(SubmittedProperties.check(request, List.of(new Requirement(limited)), DumbSlave.class));
        var node = new DumbSlave("limited", "work", new JNLPLauncher());
        node.getNodeProperties().add(new LimitedProperty());
        NodePropertyPolicy.get().replace(List.of(new Requirement(limited)));
        assertNotNull(NodePropertyPolicy.get().violation(node));
        assertNotNull(NodePropertyPolicy.unavailableReason((NodePropertyDescriptor) null));
    }

    @Test
    void filterPassesUnrelatedRequestsAndHandlesMissingRoutesWithoutMutation(JenkinsRule j) throws Exception {
        var policy = NodePropertyPolicy.get();
        policy.replace(List.of(new Requirement(ENV)));
        var filter = new PolicyFilter();
        var chain = mock(FilterChain.class);
        var response = mock(HttpServletResponse.class);
        var plain = mock(ServletRequest.class);
        filter.doFilter(plain, response, chain);
        verify(chain).doFilter(plain, response);
        var request = mock(HttpServletRequest.class);
        var nonHttp = mock(ServletResponse.class);
        filter.doFilter(request, nonHttp, chain);
        verify(chain).doFilter(request, nonHttp);
        when(request.getMethod()).thenReturn("POST");
        for (String path : new String[] {
            null,
            "/",
            "/unrelated/action",
            "/computer/unknown",
            "/computer/a/unknown",
            "/computer/missing/configSubmit",
            "/computer/a/b/c"
        }) {
            when(request.getPathInfo()).thenReturn(path);
            filter.doFilter(request, response, chain);
        }
        when(request.getPathInfo()).thenReturn("/computer/createItem");
        filter.doFilter(request, response, chain);
        when(request.getParameterValues("mode")).thenReturn(new String[] {"copy"});
        filter.doFilter(request, response, chain);
        when(request.getParameterValues("from")).thenReturn(new String[] {"missing"});
        filter.doFilter(request, response, chain);
        var node = new DumbSlave("existing", "work", new JNLPLauncher());
        j.jenkins.addNode(node);
        policy.replace(List.of(new Requirement(ENV)), new PolicyBehavior(true, false, true, true, 30));
        when(request.getPathInfo()).thenReturn("/computer/existing/configSubmit");
        filter.doFilter(request, response, chain);
        assertNotNull(policy.violation(node));
        var dispatcher = mock(RequestDispatcher.class);
        when(request.getRequestDispatcher("/node-property-rejection/")).thenReturn(dispatcher);
        when(request.getPathInfo()).thenReturn("/computer/doCreateItem");
        filter.doFilter(request, response, chain);
        verify(dispatcher).forward(request, response);
        assertEquals(1, j.jenkins.getNodes().size());
        filter.start();
        filter.stop();
    }

    @Test
    void failedPolicyWritesPreserveTheActiveSnapshotAndBlankRowsAreIgnored(JenkinsRule j) throws Exception {
        var policy = NodePropertyPolicy.get();
        policy.replace(List.of(new Requirement(ENV)));
        var file = j.jenkins.getRootDir().toPath().resolve(NodePropertyPolicy.class.getName() + ".xml");
        String saved = java.nio.file.Files.readString(file);
        java.nio.file.Files.delete(file);
        java.nio.file.Files.createDirectory(file);
        var obstacle = file.resolve("write-failure-fixture");
        java.nio.file.Files.writeString(obstacle, "Prevent replacement of this nonempty directory.");
        try {
            assertThrows(hudson.model.Descriptor.FormException.class, () -> policy.replace(List.of()));
            assertEquals(ENV, policy.getRequirements().getFirst().getPropertyId());
        } finally {
            java.nio.file.Files.delete(obstacle);
            java.nio.file.Files.delete(file);
            java.nio.file.Files.writeString(file, saved);
        }
        var request = mock(StaplerRequest2.class);
        var options = new JSONObject().element("refreshSeconds", 30);
        var json = new JSONObject().element("behavior", options).element("requirements", List.of());
        when(request.bindJSONToList(Requirement.class, json.get("requirements")))
                .thenReturn(List.of(new Requirement(null), new Requirement(" "), new Requirement(ENV)));
        when(request.bindJSON(PolicyBehavior.class, options)).thenReturn(PolicyBehavior.defaults());
        assertTrue(policy.configure(request, json));
        assertEquals(1, policy.getRequirements().size());
        policy.load();
        assertEquals(ENV, policy.getRequirements().getFirst().getPropertyId());
    }

    @Test
    void missingIntegrationsAndSavedUnknownChoicesRemainDiscoverable(JenkinsRule j) throws Exception {
        var policy = NodePropertyPolicy.get();
        policy.replace(List.of(new Requirement("missing.Property", false), new Requirement(null, false)));
        var descriptor = j.jenkins.getDescriptorByType(Requirement.DescriptorImpl.class);
        assertEquals("Required node option", descriptor.getDisplayName());
        var all = NodeProperty.all();
        var providers = NodePropertyPolicy.INTEGRATIONS.stream()
                .map(i -> NodePropertyPolicy.descriptor(i.propertyId()))
                .toList();
        providers.forEach(all::remove);
        try {
            var choices = descriptor.doFillPropertyIdItems();
            for (var integration : NodePropertyPolicy.INTEGRATIONS) {
                assertTrue(choices.stream().anyMatch(o -> o.value.equals(integration.propertyId())));
                assertTrue(NodePropertyPolicy.unavailableReason(integration.propertyId())
                        .contains(integration.minimum()));
            }
            assertTrue(choices.stream().anyMatch(o -> o.value.equals("missing.Property")));
        } finally {
            providers.forEach(all::add);
        }
        var request = mock(StaplerRequest2.class);
        var response = mock(StaplerResponse2.class);
        assertThrows(
                org.kohsuke.stapler.HttpResponses.HttpResponseException.class,
                () -> new PolicyRejection().doIndex(request, response));
        var extension = new PropertyRequirement() {
            public String propertyId() {
                return "custom";
            }

            public String check(NodeProperty<?> property) {
                return null;
            }

            public String checkSubmitted(JSONObject property, boolean creating) {
                return null;
            }
        };
        var metadata = new JSONObject();
        extension.describe(metadata);
        assertTrue(metadata.isEmpty());
    }

    @Test
    void matrixHandlesMalformedEntriesMissingIdentityAndStorageFailure(JenkinsRule j) throws Exception {
        var check = new MatrixPermissions();
        assertNotNull(check.checkSubmitted(new JSONObject(), true));
        for (String key : List.of("no-type", "UNKNOWN:user", "USER: ", "GROUP:group", "EITHER:person")) {
            var property = new JSONObject().element("data", new JSONObject().element(key, new JSONObject()));
            assertEquals(MatrixPermissions.MESSAGE, check.checkSubmitted(property, false));
        }
        assertNotNull(check.checkSubmitted(
                new JSONObject().element("data", new JSONObject().element("USER:person", "invalid")), false));
        var blank = new AuthorizationMatrixNodeProperty(
                Map.of(Computer.BUILD, java.util.Set.of(PermissionEntry.user(" "))));
        assertNotNull(check.check(blank));
        var strategy = new ProjectMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, "owner");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(strategy);
        var user = User.getById("owner", true);
        var node = spy(new DumbSlave("permissions", "work", new JNLPLauncher()));
        try (var ignored = ACL.as2(user.impersonate2())) {
            check.onCreated(node);
            assertNull(node.getNodeProperty(AuthorizationMatrixNodeProperty.class));
            NodePropertyPolicy.get().replace(List.of(new Requirement(check.propertyId())));
            check.onCreated(node);
            assertNull(node.getNodeProperty(AuthorizationMatrixNodeProperty.class));
            node.getNodeProperties().add(new AuthorizationMatrixNodeProperty(Map.of()));
            var properties = spy(node.getNodeProperties());
            doReturn(properties).when(node).getNodeProperties();
            doThrow(new IOException("simulated disk failure"))
                    .when(properties)
                    .replace(any(AuthorizationMatrixNodeProperty.class));
            assertDoesNotThrow(() -> check.onCreated(node));
            try (var missingIdentity = mockStatic(User.class)) {
                missingIdentity.when(User::current).thenReturn(null);
                assertNull(MatrixPermissions.creator());
                assertNotNull(check.checkSubmitted(new JSONObject(), true));
            }
        }
        j.jenkins.setAuthorizationStrategy(hudson.security.AuthorizationStrategy.UNSECURED);
        try (var ignored = ACL.as2(user.impersonate2())) {
            check.onCreated(node);
            assertTrue(node.getNodeProperty(AuthorizationMatrixNodeProperty.class)
                    .getGrantedPermissionEntries()
                    .isEmpty());
        }
    }

    public static final class LimitedProperty extends NodeProperty<Node> {
        @TestExtension("submittedParametersAndPropertyShapesAreUnambiguous")
        public static final class DescriptorImpl extends NodePropertyDescriptor {
            public DescriptorImpl() {
                super(LimitedProperty.class);
            }

            @Override
            public boolean isApplicable(Class<? extends Node> type) {
                return type == Jenkins.class;
            }

            @Override
            public String getDisplayName() {
                return "Controller-only test property";
            }
        }
    }
}
