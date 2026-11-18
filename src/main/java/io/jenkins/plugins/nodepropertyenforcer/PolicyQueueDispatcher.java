package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

/** Covers nodes admitted by CLI, JCasC, clouds, and other in-process callers. */
@Extension
public final class PolicyQueueDispatcher extends QueueTaskDispatcher {
    @Override
    public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
        NodePropertyPolicy policy = NodePropertyPolicy.get();
        String problem = policy.getBehavior().isBlockBuilds() ? policy.violation(node) : null;
        return problem == null ? null : new PolicyBlockage(problem);
    }

    private static final class PolicyBlockage extends CauseOfBlockage {
        private final String message;

        private PolicyBlockage(String message) {
            this.message = message;
        }

        @Override
        public String getShortDescription() {
            return message;
        }
    }
}
