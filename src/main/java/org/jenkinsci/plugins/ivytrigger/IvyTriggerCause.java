package org.jenkinsci.plugins.ivytrigger;

import org.jenkinsci.plugins.xtriggerapi.XTriggerCause;

/**
 * @author Gregory Boissinot
 */
@Deprecated
public class IvyTriggerCause extends XTriggerCause {

    public IvyTriggerCause(String causeFrom) {
        super("IvyTrigger", causeFrom, false);
    }
}
