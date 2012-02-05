package org.jenkinsci.plugins.ivytrigger;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildableItem;
import hudson.model.Node;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.service.EnvVarsResolver;
import org.jenkinsci.lib.xtrigger.AbstractTrigger;
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
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * @author Gregory Boissinot
 */
public class IvyTrigger extends AbstractTrigger implements Serializable {

    private static Logger LOGGER = Logger.getLogger(IvyTrigger.class.getName());

    private String ivyPath;

    private String ivySettingsPath;

    private String propertiesFilePath;

    private transient Map<String, String> computedDependencies = new HashMap<String, String>();

    @DataBoundConstructor
    public IvyTrigger(String cronTabSpec, String ivyPath, String ivySettingsPath, String propertiesFilePath) throws ANTLRException {
        super(cronTabSpec);
        this.ivyPath = Util.fixEmpty(ivyPath);
        this.ivySettingsPath = Util.fixEmpty(ivySettingsPath);
        this.propertiesFilePath = Util.fixEmpty(propertiesFilePath);
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

    @Override
    public void start(Node pollingNode, BuildableItem project, boolean newInstance, XTriggerLog log) {
        super.start(project, newInstance);
        try {
            log.info("Starting to record dependencies versions.");

            AbstractProject abstractProject = (AbstractProject) project;
            EnvVarsResolver varsRetriever = new EnvVarsResolver();
            Map<String, String> envVars = varsRetriever.getPollingEnvVars(abstractProject, pollingNode);

            FilePath ivyFilePath = getDescriptorFilePath(ivyPath, abstractProject, pollingNode, log, envVars);
            if (ivyFilePath == null) {
                log.error(String.format("The ivy file '%s' doesn't exist.", ivyPath));
                return;
            }
            FilePath ivySettingsFilePath = getDescriptorFilePath(ivySettingsPath, abstractProject, pollingNode, log, envVars);

            FilePath dependenciesPropertiesFilePathDescriptor = getDescriptorFilePath(propertiesFilePath, abstractProject, pollingNode, log, envVars);

            computedDependencies = getDependenciesMapForNode(pollingNode, log, ivyFilePath, ivySettingsFilePath, dependenciesPropertiesFilePathDescriptor, envVars);
        } catch (XTriggerException e) {
            //Ignore the exception process, just log it
            LOGGER.log(Level.SEVERE, e.getMessage());
        } catch (EnvInjectException e) {
            //Ignore the exception process, just log it
            LOGGER.log(Level.SEVERE, e.getMessage());
        } catch (InterruptedException e) {
            //Ignore the exception process, just log it
            LOGGER.log(Level.SEVERE, e.getMessage());
        } catch (IOException e) {
            //Ignore the exception process, just log it
            LOGGER.log(Level.SEVERE, e.getMessage());
        }
    }

    private Map<String, String> getDependenciesMapForNode(Node launcherNode,
                                                          XTriggerLog log,
                                                          FilePath ivyFilePath,
                                                          FilePath ivySettingsFilePath,
                                                          FilePath propertiesFilePath,
                                                          Map<String, String> envVars) throws IOException, InterruptedException, XTriggerException {
        Map<String, String> dependenciesMap = null;
        if (launcherNode != null) {
            FilePath launcherFilePath = launcherNode.getRootPath();
            if (launcherFilePath != null) {
                dependenciesMap = launcherFilePath.act(new IvyTriggerEvaluator(ivyFilePath, ivySettingsFilePath, propertiesFilePath, log, envVars));
            }
        }
        return dependenciesMap;
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        IvyTriggerAction action = new IvyTriggerAction((AbstractProject) job, getLogFile(), this.getDescriptor().getDisplayName());
        return Collections.singleton(action);
    }

    @Override
    protected boolean checkIfModified(Node pollingNode, XTriggerLog log) throws XTriggerException {

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
            log.error(String.format("The ivy file '%s' doesn't exist.", ivyFilePath.getRemote()));
            return false;
        }

        //Get ivysettings file
        FilePath ivySettingsFilePath = getDescriptorFilePath(ivySettingsPath, project, pollingNode, log, envVars);

        //Get Dependencies
        FilePath propertiesFilePathDescriptor = getDescriptorFilePath(propertiesFilePath, project, pollingNode, log, envVars);

        Map<String, String> newComputedDependencies;
        try {
            newComputedDependencies = getDependenciesMapForNode(pollingNode, log, ivyFilePath, ivySettingsFilePath, propertiesFilePathDescriptor, envVars);
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        } catch (InterruptedException ie) {
            throw new XTriggerException(ie);
        }
        return checkIfModifiedWithResolvedElements(log, newComputedDependencies);
    }

    @Override
    protected String getName() {
        return "IvyTrigger";
    }

    @Override
    protected Action[] getScheduledActions(Node pollingNode, XTriggerLog log) {
        return new Action[0];
    }

    private boolean checkIfModifiedWithResolvedElements(XTriggerLog log, Map<String, String> newComputedDependencies) throws XTriggerException {

        if (newComputedDependencies == null) {
            log.error("Can't record the new dependencies graph.");
            computedDependencies = null;
            return false;
        }

        if (newComputedDependencies.size() == 0) {
            log.error("Can't compute any dependencies. Check your settings.");
            computedDependencies = null;
            return false;
        }

        if (computedDependencies == null) {
            computedDependencies = newComputedDependencies;
            log.info("Recording dependencies versions. Waiting for next schedule.");
            return false;
        }

        if (computedDependencies.size() != newComputedDependencies.size()) {
            log.info(String.format("The dependencies size has changed."));
            computedDependencies = newComputedDependencies;
            return true;
        }

        for (Map.Entry<String, String> dependency : computedDependencies.entrySet()) {
            if (isChangedDependency(log, dependency, newComputedDependencies)) {
                return true;
            }
        }

        computedDependencies = newComputedDependencies;
        return false;
    }

    private boolean isChangedDependency(XTriggerLog log, Map.Entry<String, String> dependency, Map<String, String> newComputedDependencies) {
        String moduleId = dependency.getKey();
        String revision = dependency.getValue();
        String newRevision = newComputedDependencies.get(moduleId);

        log.info(String.format("Checking the dependency '%s' ...", moduleId));

        if (newRevision == null) {
            log.info("The dependency doesn't exist anymore.");
            computedDependencies = newComputedDependencies;
            return true;
        }

        if (!newRevision.equals(revision)) {
            log.info("The dependency version has changed.");
            log.info(String.format("The previous version recorded was %s.", revision));
            log.info(String.format("The new computed version is %s.", newRevision));
            computedDependencies = newComputedDependencies;
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
