package org.jenkinsci.plugins.ivytrigger;

import org.apache.ivy.util.AbstractMessageLogger;
import org.apache.ivy.util.Message;
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
        if (msg != null && msg.length() != 0) {
            log.info(msg);
        }
    }

    public void log(String msg, int level) {
        if (msg != null && msg.length() != 0) {
            if (level != Message.MSG_DEBUG || level != Message.MSG_VERBOSE) {
                log.info(msg);
            }
        }
    }

    public void rawlog(String msg, int level) {
        if (msg != null && msg.length() != 0) {
            log.info(msg);
        }
    }
}
