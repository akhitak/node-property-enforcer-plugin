package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import hudson.security.ACL;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import java.net.URI;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class RouteSecurityTest {
    @Test
    void formChecksUsePostAndStatusRemainsReadOnly(JenkinsRule j) throws Exception {
        j.jenkins.addNode(new DumbSlave("route-test", "work", new JNLPLauncher()));
        try (var client = j.createWebClient()) {
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            for (String path : List.of(
                    "descriptorByName/" + Requirement.class.getName() + "/fillPropertyIdItems",
                    "descriptorByName/" + Requirement.class.getName() + "/checkPropertyId?enabled=false",
                    "descriptorByName/" + PolicyBehavior.class.getName() + "/checkRefreshSeconds?value=30")) {
                var get = new WebRequest(URI.create(j.getURL() + path).toURL(), HttpMethod.GET);
                assertEquals(404, client.getPage(get).getWebResponse().getStatusCode(), path);
                var post = new WebRequest(client.createCrumbedUrl(path), HttpMethod.POST);
                assertEquals(200, client.getPage(post).getWebResponse().getStatusCode(), path);
            }
            for (String path : List.of(
                    "computer/route-test/node-property-status/",
                    "descriptorByName/" + QueueMessages.class.getName() + "/status?ids=1")) {
                var get = new WebRequest(URI.create(j.getURL() + path).toURL(), HttpMethod.GET);
                assertEquals(200, client.getPage(get).getWebResponse().getStatusCode(), path);
                var post = new WebRequest(client.createCrumbedUrl(path), HttpMethod.POST);
                assertEquals(404, client.getPage(post).getWebResponse().getStatusCode(), path);
            }
        }
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));
        var rejection = new PolicyRejection();
        var request = mock(StaplerRequest2.class);
        var response = mock(StaplerResponse2.class);
        try (var ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertThrows(AccessDeniedException.class, () -> rejection.doIndex(request, response));
        }
        try (var client = j.createWebClient().withBasicCredentials("reader")) {
            var post = new WebRequest(
                    client.createCrumbedUrl("descriptorByName/" + Requirement.class.getName() + "/fillPropertyIdItems"),
                    HttpMethod.POST);
            var result = client.getPage(post).getWebResponse();
            assertEquals(200, result.getStatusCode());
            assertEquals(
                    0,
                    net.sf.json.JSONObject.fromObject(result.getContentAsString())
                            .getJSONArray("values")
                            .size());
        }
    }
}
