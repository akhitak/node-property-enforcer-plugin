package io.jenkins.plugins.nodepropertyenforcer;

import hudson.Extension;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.InvisibleAction;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Records agent names for future console banners without touching build output or results. */
public final class BuildAgents extends InvisibleAction {
    // Access is synchronized; immutable snapshots also keep serialization traversal stable.
    private List<String> names = List.of();

    public synchronized List<String> getNames() {
        return List.copyOf(names);
    }

    private synchronized void rememberNode(Run<?, ?> run, String name) {
        if (names.contains(name) || names.size() >= 256) {
            return;
        }
        var updated = new ArrayList<>(names);
        updated.add(name);
        names = List.copyOf(updated);
        try {
            run.save();
        } catch (IOException e) {
            Logger.getLogger(BuildAgents.class.getName())
                    .log(Level.WARNING, "Cannot save agent history for policy banner.", e);
        }
    }

    @Extension
    public static final class Tracker implements ExecutorListener {
        private final Object historyLock = new Object();

        @Override
        public void taskStarted(Executor executor, Queue.Task task) {
            Queue.Executable executable = executor.getCurrentExecutable();
            Node node = executor.getOwner().getNode();
            Run<?, ?> run = owner(executable);
            if (run != null && node != null) {
                remember(run, node.getNodeName());
            }
        }

        @SuppressWarnings("java:S1452") // Jenkins exposes heterogeneous build types as Run<?, ?>.
        static Run<?, ?> owner(Queue.Executable executable) {
            if (executable instanceof Run<?, ?> run) {
                return run;
            }
            if (executable != null && executable.getParent().getOwnerExecutable() instanceof Run<?, ?> run) {
                return run;
            }
            return null;
        }

        private void remember(Run<?, ?> run, String name) {
            BuildAgents history;
            synchronized (historyLock) {
                history = run.getAction(BuildAgents.class);
                if (history == null) {
                    history = new BuildAgents();
                    run.addAction(history);
                }
            }
            history.rememberNode(run, name);
        }
    }
}
