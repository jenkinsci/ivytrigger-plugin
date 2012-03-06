package org.jenkinsci.plugins.ivytrigger;

import org.jenkinsci.lib.xtrigger.XTriggerContext;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Gregory Boissinot
 */
public class IvyTriggerContext implements XTriggerContext {

    private Map<String, IvyDependencyValue> dependencies = new HashMap<String, IvyDependencyValue>();

    public IvyTriggerContext(Map<String, IvyDependencyValue> dependencies) {
        this.dependencies = dependencies;
    }

    public Map<String, IvyDependencyValue> getDependencies() {
        return dependencies;
    }
}
