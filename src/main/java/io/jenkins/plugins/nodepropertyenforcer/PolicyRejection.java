package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.InvisibleAction;
import hudson.model.RootAction;
import jakarta.servlet.ServletException;
import java.io.IOException;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/** Native Jenkins error page; no untrusted text is interpreted as markup. */
@Extension
public final class PolicyRejection extends InvisibleAction implements RootAction {
    @Override
    public String getUrlName() {
        return "node-property-rejection";
    }

    @RequirePOST
    public void doIndex(StaplerRequest2 request, StaplerResponse2 response) throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.READ);
        if (request.getAttribute("nodePropertyPolicy.error") == null) {
            throw HttpResponses.notFound();
        }
        response.setStatus(400);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        request.getView(this, "rejected.jelly").forward(request, response);
    }
}
