package org.jenkinsci.plugins.ivytrigger;

import hudson.model.TaskListener;

import java.io.Serializable;

/**
 * @author Gregory Boissinot
 */
public class IvyTriggerLog implements Serializable {

    private TaskListener listener;

    public IvyTriggerLog(TaskListener listener) {
        this.listener = listener;
    }

    public void info(String message) {
        listener.getLogger().println(message);
    }

    public void error(String message) {
        listener.getLogger().println("[ERROR] - " + message);
    }

}
