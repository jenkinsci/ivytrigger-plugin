package org.jenkinsci.plugins.ivytrigger;

import org.apache.ivy.util.AbstractMessageLogger;
import org.jenkinsci.lib.xtrigger.XTriggerLog;

/**
 * @author Gregory Boissinot
 */
public class IvyTriggerResolverLog extends AbstractMessageLogger {

    private XTriggerLog log;

    public IvyTriggerResolverLog(XTriggerLog xTriggerLog) {
        this.log = xTriggerLog;
    }

    @Override
    protected void doProgress() {
        log.info(".");
    }

    @Override
    protected void doEndProgress(String msg) {
        log.info(msg);
    }

    public void log(String msg, int level) {
        log.info(msg);
    }

    public void rawlog(String msg, int level) {
        log.info(msg);
    }
}
