package org.jenkinsci.plugins.ivytrigger;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Node;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.service.EnvVarsResolver;
import org.jenkinsci.lib.xtrigger.AbstractTriggerByFullContext;
import org.jenkinsci.lib.xtrigger.XTriggerDescriptor;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;
import org.jenkinsci.plugins.ivytrigger.service.IvyTriggerEvaluator;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;


/**
 * @author Gregory Boissinot
 */
public class IvyTrigger extends AbstractTriggerByFullContext<IvyTriggerContext> implements Serializable {

    private String ivyPath;

    private String ivySettingsPath;

    private String propertiesFilePath;

    private String propertiesContent;

    @DataBoundConstructor
    public IvyTrigger(String cronTabSpec, String ivyPath, String ivySettingsPath, String propertiesFilePath, String propertiesContent) throws ANTLRException {
        super(cronTabSpec);
        this.ivyPath = Util.fixEmpty(ivyPath);
        this.ivySettingsPath = Util.fixEmpty(ivySettingsPath);
        this.propertiesFilePath = Util.fixEmpty(propertiesFilePath);
        this.propertiesContent = Util.fixEmpty(propertiesContent);
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

    @Override
    public Collection<? extends Action> getProjectActions() {
        IvyTriggerAction action = new IvyTriggerAction((AbstractProject) job, getLogFile(), this.getDescriptor().getDisplayName());
        return Collections.singleton(action);
    }

    @Override
    public boolean isContextOnStartupFetched() {
        return false;
    }

    @Override
    protected IvyTriggerContext getContext(Node pollingNode, XTriggerLog log) throws XTriggerException {
        AbstractProject project = (AbstractProject) job;

        EnvVarsResolver varsRetriever = new EnvVarsResolver();
        Map<String, String> envVars;
        try {
            envVars = varsRetriever.getPollingEnvVars(project, pollingNode);
        } catch (EnvInjectException e) {
            throw new XTriggerException(e);
        }

        //Get ivy file
        FilePath ivyFilePath = getDescriptorFilePath(ivyPath, project, pollingNode, log, envVars);
        if (ivyFilePath == null) {
            log.error(String.format("The ivy file path '%s' doesn't exist.", ivyPath));
            return new IvyTriggerContext(null);
        }

        //Get ivysettings file
        FilePath ivySettingsFilePath = getDescriptorFilePath(ivySettingsPath, project, pollingNode, log, envVars);

        //Get properties info
        FilePath propertiesFilePathDescriptor = getDescriptorFilePath(propertiesFilePath, project, pollingNode, log, envVars);
        String propertiesContentResolved = Util.replaceMacro(propertiesContent, envVars);

        Map<String, IvyDependencyValue> dependencies;
        try {
            dependencies = getDependenciesMapForNode(pollingNode, log, ivyFilePath, ivySettingsFilePath, propertiesFilePathDescriptor, propertiesContentResolved, envVars);
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
                                                                      FilePath propertiesFilePath,
                                                                      String propertiesContent,
                                                                      Map<String, String> envVars) throws IOException, InterruptedException, XTriggerException {
        Map<String, IvyDependencyValue> dependenciesMap = null;
        if (launcherNode != null) {
            FilePath launcherFilePath = launcherNode.getRootPath();
            if (launcherFilePath != null) {
                dependenciesMap = launcherFilePath.act(new IvyTriggerEvaluator(job.getName(), ivyFilePath, ivySettingsFilePath, propertiesFilePath, propertiesContent, log, envVars));
            }
        }
        return dependenciesMap;
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
        Map<String, IvyDependencyValue> newComputedDependencies = newIvyTriggerContext.getDependencies();

        //Check pre-requirements
        if (newComputedDependencies == null) {
            log.error("Can't record the new dependencies graph.");
            return false;
        }

        if (newComputedDependencies.size() == 0) {
            log.error("Can't compute any dependencies. Check your settings.");
            return false;
        }

        //Display all resolved dependencies
        for (Map.Entry<String, IvyDependencyValue> dependency : newComputedDependencies.entrySet()) {
            log.info(String.format("Checking resolved dependency %s ...", dependency.getKey()));
        }

        if (previousDependencies == null) {
            log.info("Recording dependencies state. Waiting for next schedule to compare changes between polls.");
            return false;
        }

        if (previousDependencies.size() != newComputedDependencies.size()) {
            log.info(String.format("The number of computed dependencies has changed."));
            return true;
        }

        //Check if there is at least one change
        for (Map.Entry<String, IvyDependencyValue> dependency : previousDependencies.entrySet()) {
            if (isDependencyChanged(log, dependency, newComputedDependencies)) {
                return true;
            }
        }

        return false;
    }

    private boolean isDependencyChanged(XTriggerLog log,
                                        Map.Entry<String, IvyDependencyValue> previousDependency,
                                        Map<String, IvyDependencyValue> newComputedDependencies) {


        String moduleId = previousDependency.getKey();
        IvyDependencyValue previousDependencyValue = previousDependency.getValue();
        IvyDependencyValue newDependencyValue = newComputedDependencies.get(moduleId);

        //Check if the previous dependency exists anymore
        if (newDependencyValue == null) {
            log.info(String.format("The dependency doesn't exist anymore."));
            return true;
        }

        //Check if the revision has changed
        String previousRevision = previousDependencyValue.getRevision();
        String newRevision = newDependencyValue.getRevision();
        if (!newRevision.equals(previousRevision)) {
            log.info("The dependency version has changed.");
            log.info(String.format("The previous version recorded was %s.", previousRevision));
            log.info(String.format("The new computed version is %s.", newRevision));
            return true;
        }

        //Check if artifacts list has changed
        List<IvyArtifactValue> previousArtifactValueList = previousDependencyValue.getArtifacts();
        List<IvyArtifactValue> newArtifactValueList = newDependencyValue.getArtifacts();

        //Display all resolved artifacts
        log.info("\n");
        for (IvyArtifactValue artifactValue : newArtifactValueList) {
            log.info(String.format("Checking resolved artifact %s ...", artifactValue.getName()));
        }

        if (previousArtifactValueList.size() != newArtifactValueList.size()) {
            log.info("The number of artifacts of the dependency has changed.");
        }

        //Check if there is at least one change to previous recording artifacts
        for (IvyArtifactValue ivyArtifactValue : previousArtifactValueList) {
            if (isArtifactsChanged(log, ivyArtifactValue, newArtifactValueList)) {
                return true;
            }
        }

        return false;
    }

    private boolean isArtifactsChanged(XTriggerLog log, IvyArtifactValue previousIvyArtifactValue, List<IvyArtifactValue> newArtifactValueList) {

        //Get the new artifact with same coordinates
        IvyArtifactValue newIvyArtifactValue = null;
        boolean stop = false;
        int i = 0;
        while (!stop && i < newArtifactValueList.size()) {
            IvyArtifactValue ivyArtifactValue = newArtifactValueList.get(i);
            if (ivyArtifactValue.getName().equals(previousIvyArtifactValue.getName())) {
                newIvyArtifactValue = ivyArtifactValue;
                stop = true;
            }
            i++;
        }

        //--Check if there are changes

        //Check if the artifact still exist
        if (newIvyArtifactValue == null) {
            log.info(String.format("The previous artifact %s doesn't exist anymore.", previousIvyArtifactValue.getName()));
            return true;
        }

        //Check the publication date
        long previousPublicationDate = previousIvyArtifactValue.getLastModificationDate();
        long newPublicationDate = newIvyArtifactValue.getLastModificationDate();
        if (previousPublicationDate != newPublicationDate) {
            log.info("The dependency version has changed.");
            log.info(String.format("The previous publication date recorded was %s.", previousPublicationDate));
            log.info(String.format("The new computed publication date is %s.", newPublicationDate));
            return true;
        }

        return false;
    }

    private FilePath getDescriptorFilePath(String filePath,
                                           AbstractProject job,
                                           Node pollingNode,
                                           XTriggerLog log,
                                           Map<String, String> envVars)
            throws XTriggerException {
        try {

            //If the current file path is not specified, don't compute it
            if (filePath == null) {
                return null;
            }

            //0-- Resolve variables for the path
            String resolvedFilePath = Util.replaceMacro(filePath, envVars);

            //--Try to look for the file

            //1-- Try to find the file in the last workspace if any
            FilePath workspace = job.getSomeWorkspace();
            if (workspace != null) {
                FilePath ivyDescPath = workspace.child(resolvedFilePath);
                if (ivyDescPath.exists()) {
                    return ivyDescPath;
                }
            }

            //The slave is off
            if (pollingNode == null) {
                //try a full path from the master
                File file = new File(resolvedFilePath);
                if (file.exists()) {
                    return new FilePath(file);
                }
                log.error(String.format("Can't find the file '%s'.", resolvedFilePath));
                return null;
            } else {

                FilePath filePathObject = new FilePath(pollingNode.getRootPath(), resolvedFilePath);

                if (filePathObject.exists()) {
                    return filePathObject;
                }

                log.error(String.format("Can't find the file '%s'.", resolvedFilePath));
                return null;
            }

        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        } catch (InterruptedException ie) {
            throw new XTriggerException(ie);
        }
    }

    /**
     * Gets the triggering log file
     *
     * @return the trigger log
     */
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
            return "[IvyTrigger] - Poll with an Ivy script";
        }
    }


}
