package org.jenkinsci.plugins.ivytrigger;

import org.jenkinsci.lib.xtrigger.XTriggerContext;

import java.util.Map;

/**
 * @author Gregory Boissinot
 */
public class IvyTriggerContext implements XTriggerContext {

    private final Map<String, IvyDependencyValue> dependencies;

    public IvyTriggerContext(Map<String, IvyDependencyValue> dependencies) {
        this.dependencies = dependencies;
    }

    public Map<String, IvyDependencyValue> getDependencies() {
        return dependencies;
    }
}
