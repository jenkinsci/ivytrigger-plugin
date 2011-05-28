package org.jenkinsci.plugins.ivytrigger;

/**
 * @author Gregory Boissinot
 */
public class IvyTriggerException extends Exception {

    public IvyTriggerException() {
    }

    public IvyTriggerException(String s) {
        super(s);
    }

    public IvyTriggerException(String s, Throwable throwable) {
        super(s, throwable);
    }

    public IvyTriggerException(Throwable throwable) {
        super(throwable);
    }
}
