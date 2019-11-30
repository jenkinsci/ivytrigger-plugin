package org.jenkinsci.plugins.ivytrigger;

import java.io.Serializable;
import java.util.List;

/**
 * @author Gregory Boissinot
 */
public class IvyDependencyValue implements Serializable {

    private final String revision;

    private final List<IvyArtifactValue> artifacts;

    public IvyDependencyValue(String revision, List<IvyArtifactValue> artifacts) {
        this.revision = revision;
        this.artifacts = artifacts;
    }

    public String getRevision() {
        return revision;
    }

    public List<IvyArtifactValue> getArtifacts() {
        return artifacts;
    }
}
