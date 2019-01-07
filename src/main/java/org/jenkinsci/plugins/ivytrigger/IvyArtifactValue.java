package org.jenkinsci.plugins.ivytrigger;

import java.io.Serializable;

/**
 * @author Gregory Boissinot
 */
public class IvyArtifactValue implements Serializable {

    private final String name;

    private final String extension;

    private final long lastModificationDate;

    public IvyArtifactValue(String name, String extension, long lastModificationDate) {
        this.name = name;
        this.extension = extension;
        this.lastModificationDate = lastModificationDate;
    }

    public String getFullName() {
        if (extension != null) {
            return String.format("%s.%s", name, extension);
        }
        return getName();
    }

    public String getName() {
        return name;
    }

    public String getExtension() {
        return extension;
    }

    public long getLastModificationDate() {
        return lastModificationDate;
    }
}
