package org.jenkinsci.plugins.ivytrigger;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildableItem;
import hudson.model.Node;
import hudson.util.NullStream;
import hudson.util.StreamTaskListener;
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

    private transient Map<String, String> computedDependencies = new HashMap<String, String>();

    @DataBoundConstructor
    public IvyTrigger(String cronTabSpec, String ivyPath, String ivySettingsPath) throws ANTLRException {
        super(cronTabSpec);
        this.ivyPath = Util.fixEmpty(ivyPath);
        this.ivySettingsPath = Util.fixEmpty(ivySettingsPath);
    }

    @Override
    public void start(BuildableItem project, boolean newInstance) {
        super.start(project, newInstance);
        try {
            XTriggerLog log = new XTriggerLog(new StreamTaskListener(new NullStream()));
            log.info("Starting to record dependencies versions.");

            AbstractProject abstractProject = (AbstractProject) job;
            Node launcherNode = getPollingNode(log);
            if (launcherNode == null) {
                log.info("Can't find any complete active node. Checking again in next polling schedule.");
                return;
            }
            if (launcherNode.getRootPath() == null) {
                log.info("The running slave might be offline at the moment. Waiting for next schedule.");
                return;
            }

            EnvVarsResolver varsRetriever = new EnvVarsResolver();
            Map<String, String> enVars = varsRetriever.getPollingEnvVars(abstractProject, launcherNode);

            FilePath ivyFilePath = getDescriptorFilePath(ivyPath, abstractProject, launcherNode, log, enVars);
            if (ivyFilePath == null) {
                log.error(String.format("The ivy file '%s' doesn't exist.", ivyPath));
                return;
            }
            FilePath ivySettingsFilePath = getDescriptorFilePath(ivySettingsPath, abstractProject, launcherNode, log, enVars);

            computedDependencies = getDependenciesMapForNode(launcherNode, log, ivyFilePath, ivySettingsFilePath);
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
                                                          FilePath ivySettingsFilePath) throws IOException, InterruptedException, XTriggerException {
        Map<String, String> dependenciesMap = null;
        if (launcherNode != null) {
            FilePath launcherFilePath = launcherNode.getRootPath();
            if (launcherFilePath != null) {
                dependenciesMap = launcherFilePath.act(new IvyTriggerEvaluator(ivyFilePath, ivySettingsFilePath, log));
            }
        }
        return dependenciesMap;
    }


    @SuppressWarnings("unused")
    public String getIvyPath() {
        return ivyPath;
    }

    @SuppressWarnings("unused")
    public String getIvySettingsPath() {
        return ivySettingsPath;
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        IvyTriggerAction action = new IvyTriggerAction((AbstractProject) job, getLogFile(), this.getDescriptor().getDisplayName());
        return Collections.singleton(action);
    }

    @Override
    protected boolean checkIfModified(XTriggerLog log) throws XTriggerException {

        AbstractProject project = (AbstractProject) job;

        Node launcherNode = getPollingNode(log);
        if (launcherNode == null) {
            log.info("Can't find any complete active node for the polling action. Maybe slaves are not yet active at this time or the number of executor of the master is 0. Checking again in next polling schedule.");
            return false;
        }

        if (launcherNode.getRootPath() == null) {
            log.info("The running slave might be offline at the moment. Waiting for next schedule.");
            return false;
        }

        EnvVarsResolver varsRetriever = new EnvVarsResolver();
        Map<String, String> envVars = null;
        try {
            envVars = varsRetriever.getPollingEnvVars(project, launcherNode);
        } catch (EnvInjectException e) {
            throw new XTriggerException(e);
        }

        //Get ivy file
        FilePath ivyFilePath = getDescriptorFilePath(ivyPath, project, launcherNode, log, envVars);
        if (ivyFilePath == null) {
            log.error(String.format("The ivy file '%s' doesn't exist.", ivyFilePath.getRemote()));
            return false;
        }

        //Get ivysettings file
        FilePath ivySettingsFilePath = getDescriptorFilePath(ivySettingsPath, project, launcherNode, log, envVars);

        Map<String, String> newComputedDependencies;
        try {
            newComputedDependencies = getDependenciesMapForNode(launcherNode, log, ivyFilePath, ivySettingsFilePath);
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

        log.info(String.format("\nChecking the dependency '%s' ...", moduleId));

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
                                           Node launcherNode,
                                           XTriggerLog log,
                                           Map<String, String> envVars)
            throws XTriggerException {
        try {

            if (filePath == null) {
                return null;
            }

            //0-- Resolve variables for the path
            String resolvedFilePath = resolveEnvVars(filePath, job, launcherNode);

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
            if (launcherNode == null) {
                //try a full path from the master
                File file = new File(resolvedFilePath);
                if (file.exists()) {
                    return new FilePath(file);
                }
                log.error(String.format("Can't find the file '%s'.", resolvedFilePath));
                return null;
            } else {

                FilePath filePathObject = new FilePath(launcherNode.getRootPath(), resolvedFilePath);

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
