package org.jenkinsci.plugins.ivytrigger;

import org.jenkinsci.lib.xtrigger.XTriggerCause;

/**
 * @author Gregory Boissinot
 */
@Deprecated
public class IvyTriggerCause extends XTriggerCause {

    public IvyTriggerCause(String causeFrom) {
        super("IvyTrigger", causeFrom);

    }
}
