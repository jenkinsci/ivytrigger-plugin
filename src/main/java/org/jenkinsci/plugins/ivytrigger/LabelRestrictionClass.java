package org.jenkinsci.plugins.ivytrigger;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Gregory Boissinot
 */
public class LabelRestrictionClass {

    private final String triggerLabel;

    @DataBoundConstructor
    public LabelRestrictionClass(String triggerLabel) {
        this.triggerLabel = triggerLabel;
    }

    public String getTriggerLabel() {
        return triggerLabel;
    }
}
