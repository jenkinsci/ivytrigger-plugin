package org.jenkinsci.plugins.ivytrigger;

import org.jenkinsci.lib.xtrigger.XTriggerContext;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Gregory Boissinot
 */
public class IvyTriggerContext implements XTriggerContext {

    private Map<String, String> dependencies = new HashMap<String, String>();

    public IvyTriggerContext(Map<String, String> dependencies) {
        this.dependencies = dependencies;
    }

    public Map<String, String> getDependencies() {
        return dependencies;
    }
}
