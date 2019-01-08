package org.jenkinsci.plugins.ivytrigger;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Node;

import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.service.EnvVarsResolver;
import org.jenkinsci.lib.xtrigger.AbstractTriggerByFullContext;
import org.jenkinsci.lib.xtrigger.XTriggerDescriptor;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;
import org.jenkinsci.plugins.ivytrigger.util.FilePathFactory;
import org.jenkinsci.plugins.ivytrigger.util.PropertiesFileContentExtractor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;


/**
 * @author Gregory Boissinot
 */
public class IvyTrigger extends AbstractTriggerByFullContext<IvyTriggerContext> implements Serializable {

    private final String ivyPath;

    private final String ivySettingsPath;

    private final String propertiesFilePath;

    private final String propertiesContent;

    private final boolean debug;

    private final boolean labelRestriction;

    private final boolean enableConcurrentBuild;

    private final boolean downloadArtifacts;

    @DataBoundConstructor
    public IvyTrigger(String cronTabSpec, String ivyPath, String ivySettingsPath, String propertiesFilePath, String propertiesContent, LabelRestrictionClass labelRestriction, boolean enableConcurrentBuild, boolean debug, boolean downloadArtifacts) throws ANTLRException {
        super(cronTabSpec, (labelRestriction == null) ? null : labelRestriction.getTriggerLabel(), enableConcurrentBuild);
        this.ivyPath = Util.fixEmptyAndTrim(ivyPath);
        this.ivySettingsPath = Util.fixEmptyAndTrim(ivySettingsPath);
        this.propertiesFilePath = Util.fixEmptyAndTrim(propertiesFilePath);
        this.propertiesContent = Util.fixEmptyAndTrim(propertiesContent);
        this.debug = debug;
        this.downloadArtifacts = downloadArtifacts;
        this.labelRestriction = labelRestriction != null;
        this.enableConcurrentBuild = enableConcurrentBuild;
    }

    @SuppressWarnings("unused")
    public String getIvyPath() {
        return ivyPath;
    }

    @SuppressWarnings("unused")
    public String getIvySettingsPath() {
        return ivySettingsPath;
    }

    @SuppressWarnings("unused")
    public String getPropertiesFilePath() {
        return propertiesFilePath;
    }

    @SuppressWarnings("unused")
    public String getPropertiesContent() {
        return propertiesContent;
    }

    @SuppressWarnings("unused")
    public boolean isDebug() {
        return debug;
    }

    @SuppressWarnings("unused")
    public boolean isDownloadArtifacts() {
        return downloadArtifacts;
    }

    public boolean isLabelRestriction() {
        return labelRestriction;
    }

    public boolean isEnableConcurrentBuild() {
        return enableConcurrentBuild;
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        IvyTriggerAction action = new InternalIvyTriggerAction(this.getDescriptor().getDisplayName());
        return Collections.singleton(action);
    }


    public final class InternalIvyTriggerAction extends IvyTriggerAction {

        private final transient String label;

        public InternalIvyTriggerAction(String label) {
            this.label = label;
        }

        @SuppressWarnings("unused")
        public AbstractProject<?, ?> getOwner() {
            return (AbstractProject) job;
        }

        @Override
        public String getIconFileName() {
            return "clipboard.gif";
        }

        @Override
        public String getDisplayName() {
            return "IvyTrigger Log";
        }

        @Override
        public String getUrlName() {
            return "ivyTriggerPollLog";
        }

        @SuppressWarnings("unused")
        public String getLabel() {
            return label;
        }

        @SuppressWarnings("unused")
        public String getLog() throws IOException {
            return Util.loadFile(getLogFile());
        }

        @SuppressWarnings("unused")
        public void writeLogTo(XMLOutput out) throws IOException {
            new AnnotatedLargeText<InternalIvyTriggerAction>(getLogFile(), Charset.defaultCharset(), true, this).writeHtmlTo(0, out.asWriter());
        }
    }


    @Override
    public boolean isContextOnStartupFetched() {
        return false;
    }

    @Override
    protected IvyTriggerContext getContext(Node pollingNode, XTriggerLog log) throws XTriggerException {

        log.info(String.format("Given job Ivy file value: %s", ivyPath));
        log.info(String.format("Given job Ivy settings file value: %s", ivySettingsPath));

        AbstractProject project = (AbstractProject) job;
        EnvVarsResolver varsRetriever = new EnvVarsResolver();
        Map<String, String> envVars;
        try {
            envVars = varsRetriever.getPollingEnvVars(project, pollingNode);
        } catch (EnvInjectException e) {
            throw new XTriggerException(e);
        }

        //Get ivy file and get ivySettings file
        FilePathFactory filePathFactory = new FilePathFactory();
        FilePath ivyFilePath = filePathFactory.getDescriptorFilePath(ivyPath, project, pollingNode, log, envVars);
        final URL ivySettingsUrl = getRemoteURL(ivySettingsPath, log);
        FilePath ivySettingsFilePath = ivySettingsUrl != null ? null : filePathFactory
                .getDescriptorFilePath(ivySettingsPath, project, pollingNode, log, envVars);

        if (ivyFilePath == null) {
            log.error("You have to provide a valid Ivy file.");
            return new IvyTriggerContext(null);
        }
        if (ivySettingsFilePath == null && ivySettingsUrl == null) {
            log.error("You have to provide a valid IvySettings file or URL.");
            return new IvyTriggerContext(null);
        }

        log.info(String.format("Resolved job Ivy file value: %s", ivyFilePath.getRemote()));
        log.info(String.format(
                "Resolved job Ivy settings file value: %s",
                ivySettingsUrl == null ? ivySettingsFilePath.getRemote() : ivySettingsUrl
                        .toString()));

        if ( downloadArtifacts ) {
            log.info("Artifacts in dependencies will be downloaded.");
        }

        PropertiesFileContentExtractor propertiesFileContentExtractor = new PropertiesFileContentExtractor(new FilePathFactory());
        String propertiesFileContent = propertiesFileContentExtractor.extractPropertiesFileContents(propertiesFilePath, project, pollingNode, log, envVars);
        String propertiesContentResolved = Util.replaceMacro(propertiesContent, envVars);

        Map<String, IvyDependencyValue> dependencies;
        try {
            FilePath temporaryPropertiesFilePath = pollingNode.getRootPath().createTextTempFile("props", "props", propertiesFileContent);
            log.info("Temporary properties file path: " + temporaryPropertiesFilePath.getName());
            dependencies = getDependenciesMapForNode(pollingNode, log, ivyFilePath, ivySettingsFilePath, ivySettingsUrl, temporaryPropertiesFilePath, propertiesContentResolved, envVars);
            temporaryPropertiesFilePath.delete();
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        } catch (InterruptedException ie) {
            throw new XTriggerException(ie);
        }
        return new IvyTriggerContext(dependencies);
    }

    private Map<String, IvyDependencyValue> getDependenciesMapForNode(Node launcherNode,
                                                                      XTriggerLog log,
                                                                      FilePath ivyFilePath,
                                                                      FilePath ivySettingsFilePath,
                                                                      URL ivySettingsURL,
                                                                      FilePath propertiesFilePath,
                                                                      String propertiesContent,
                                                                      Map<String, String> envVars) throws IOException, InterruptedException, XTriggerException {
        Map<String, IvyDependencyValue> dependenciesMap = null;
        if (launcherNode != null) {
            FilePath launcherFilePath = launcherNode.getRootPath();
            if (launcherFilePath != null) {
                dependenciesMap = launcherFilePath.act(new IvyTriggerEvaluator(job.getName(), ivyFilePath, ivySettingsFilePath, ivySettingsURL, propertiesFilePath, propertiesContent, log, debug, downloadArtifacts, envVars));
            }
        }
        return dependenciesMap;
    }

    /**
     * Method tests, whether the string specifies the local file or an URL. In
     * the second case, URL is returned.
     * @param filename filename to test
     * @param log log for he logging
     * @return URL, if the specified filename is an URL, <code>null</code>
     *         otherwise
     */
    private static URL getRemoteURL(String filename, XTriggerLog log) {
        URL settingsUrl;
        try {
            settingsUrl = new URL(filename);
        } catch (MalformedURLException e) {
            log.info("URL is not well-formatted. Assuming it is a local file: " + filename);
            return null;
        }
        final String scheme = settingsUrl.getProtocol();
        if (scheme == null) {
            return null;
        } else {
            return settingsUrl;
        }
    }

    @Override
    protected String getName() {
        return "IvyTrigger";
    }

    @Override
    protected Action[] getScheduledActions(Node pollingNode, XTriggerLog log) {
        return new Action[0];
    }

    @Override
    protected boolean checkIfModified(IvyTriggerContext previousIvyTriggerContext,
                                      IvyTriggerContext newIvyTriggerContext,
                                      XTriggerLog log)
            throws XTriggerException {

        Map<String, IvyDependencyValue> previousDependencies = previousIvyTriggerContext.getDependencies();

        if (previousDependencies == null) {
            log.error("Can't compute files to check if there are modifications.");
            resetOldContext(previousIvyTriggerContext);
            return false;
        }

        Map<String, IvyDependencyValue> newComputedDependencies = newIvyTriggerContext.getDependencies();

        //Check pre-requirements
        if (newComputedDependencies == null) {
            log.error("Can't record the resolved dependencies graph.");
            resetOldContext(previousIvyTriggerContext);
            return false;
        }

        if (newComputedDependencies.size() == 0) {
            log.error("Can't record any dependencies. Check your settings.");
            resetOldContext(previousIvyTriggerContext);
            return false;
        }

        //Display all resolved dependencies
        for (Map.Entry<String, IvyDependencyValue> dependency : newComputedDependencies.entrySet()) {
            log.info(String.format("Resolved dependency %s ...", dependency.getKey()));
        }

        if (previousDependencies == null) {
            log.info("\nRecording dependencies state. Waiting for next schedule to compare changes between polls.");
            setNewContext(newIvyTriggerContext);
            return false;
        }

        if (previousDependencies.size() != newComputedDependencies.size()) {
            log.info("\nThe number of resolved dependencies has changed.");
            setNewContext(newIvyTriggerContext);
            return true;
        }

        //Check if there is at least one change
        log.info("\nChecking comparison to previous recorded dependencies.");
        for (Map.Entry<String, IvyDependencyValue> dependency : previousDependencies.entrySet()) {
            if (isDependencyChanged(log, dependency, newComputedDependencies)) {
                setNewContext(newIvyTriggerContext);
                return true;
            }
        }

        setNewContext(newIvyTriggerContext);
        return false;
    }

    private boolean isDependencyChanged(XTriggerLog log,
                                        Map.Entry<String, IvyDependencyValue> previousDependency,
                                        Map<String, IvyDependencyValue> newComputedDependencies) {

        String dependencyId = previousDependency.getKey();
        log.info(String.format("Checking previous recording dependency %s", dependencyId));

        IvyDependencyValue previousDependencyValue = previousDependency.getValue();
        IvyDependencyValue newDependencyValue = newComputedDependencies.get(dependencyId);

        //Check if the previous dependency exists anymore
        if (newDependencyValue == null) {
            log.info(String.format("....The previous dependency %s doesn't exist anymore.", dependencyId));
            return true;
        }

        //Check if the revision has changed
        String previousRevision = previousDependencyValue.getRevision();
        String newRevision = newDependencyValue.getRevision();
        if (!newRevision.equals(previousRevision)) {
            log.info("....The dependency version has changed.");
            log.info(String.format("....The previous version recorded was %s.", previousRevision));
            log.info(String.format("....The new computed version is %s.", newRevision));
            return true;
        }

        //Check if artifacts list has changed
        List<IvyArtifactValue> previousArtifactValueList = previousDependencyValue.getArtifacts();
        List<IvyArtifactValue> newArtifactValueList = newDependencyValue.getArtifacts();

        //Display all resolved artifacts
        for (IvyArtifactValue artifactValue : newArtifactValueList) {
            log.info(String.format("..Dependency resolved artifact: %s", artifactValue.getFullName()));
        }

        if (previousArtifactValueList.size() != newArtifactValueList.size()) {
            log.info("....The number of artifacts of the dependency has changed.");
        }

        // Check if there is at least one change to previous recording artifacts
        // Only do this if we've been told to download artifacts. Otherwise there is
        // nothing to compare.
        if ( downloadArtifacts ) {
            log.info("...Checking comparison to previous recorded artifacts.");
            for ( IvyArtifactValue ivyArtifactValue : previousArtifactValueList ) {
                if ( isArtifactsChanged(log, ivyArtifactValue, newArtifactValueList) ) {
                    return true;
                }
            }
        }
        else {
            log.info("...Artifacts were not configured for download, no individual artifact checks made.");
        }

        return false;
    }

    private boolean isArtifactsChanged(XTriggerLog log, IvyArtifactValue previousIvyArtifactValue, List<IvyArtifactValue> newArtifactValueList) {

        log.info(String.format("....Checking previous recording artifact %s", previousIvyArtifactValue.getFullName()));

        //Get the new artifact with same coordinates
        IvyArtifactValue newIvyArtifactValue = null;
        boolean stop = false;
        int i = 0;
        while (!stop && i < newArtifactValueList.size()) {
            IvyArtifactValue ivyArtifactValue = newArtifactValueList.get(i);
            if (ivyArtifactValue.getFullName().equals(previousIvyArtifactValue.getFullName())) {
                newIvyArtifactValue = ivyArtifactValue;
                stop = true;
            }
            i++;
        }

        //--Check if there are changes

        //Check if the artifact still exist
        if (newIvyArtifactValue == null) {
            log.info(String.format("....The previous artifact %s doesn't exist anymore.", previousIvyArtifactValue.getFullName()));
            return true;
        }

        //Check the publication date
        long previousPublicationDate = previousIvyArtifactValue.getLastModificationDate();
        long newPublicationDate = newIvyArtifactValue.getLastModificationDate();
        if (previousPublicationDate != newPublicationDate) {
            log.info("....The artifact version of the dependency has changed.");
            log.info(String.format("....The previous publication date recorded was %s.", new Date(previousPublicationDate)));
            log.info(String.format("....The new computed publication date is %s.", new Date(newPublicationDate)));
            return true;
        }

        log.info(String.format("....No changes for the %s artifact", newIvyArtifactValue.getFullName()));
        return false;
    }


    /**
     * Gets the triggering log file
     *
     * @return the trigger log
     */
    @Override
    protected File getLogFile() {
        return new File(job.getRootDir(), "ivy-polling.log");
    }

    @Override
    protected boolean requiresWorkspaceForPolling() {
        return true;
    }

    @Override
    public String getCause() {
        return "Ivy Dependency trigger";
    }

    @Extension
    @SuppressWarnings("unused")
    public static class IvyScriptTriggerDescriptor extends XTriggerDescriptor {

        @Override
        public String getHelpFile() {
            return "/plugin/ivytrigger/help.html";
        }

        @Override
        public String getDisplayName() {
            return "IvyTrigger - Poll with an Ivy script";
        }
    }

}
