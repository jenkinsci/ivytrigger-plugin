package org.jenkinsci.plugins.ivytrigger;

import java.io.Serializable;

/**
 * @author Gregory Boissinot
 */
public class IvyArtifactValue implements Serializable {

    private String name;

    private long lastModificationDate;

    public IvyArtifactValue(String name, long lastModificationDate) {
        this.name = name;
        this.lastModificationDate = lastModificationDate;
    }

    public String getName() {
        return name;
    }

    public long getLastModificationDate() {
        return lastModificationDate;
    }
}


