package org.jenkinsci.plugins.ivytrigger;

import hudson.model.Cause;

/**
 * @author Gregory Boissinot
 */
public class IvyTriggerCause extends Cause {

    @Override
    public String getShortDescription() {
        return "[IvyTrigger] - Ivy Dependency trigger";
    }
}
