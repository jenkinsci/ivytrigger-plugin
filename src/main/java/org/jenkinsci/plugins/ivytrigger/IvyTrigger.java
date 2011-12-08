package org.jenkinsci.plugins.ivytrigger;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.*;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.SequentialExecutionQueue;
import hudson.util.StreamTaskListener;
import org.jenkinsci.plugins.ivytrigger.service.IvyTriggerEnvVarsRetriever;
import org.jenkinsci.plugins.ivytrigger.service.IvyTriggerEvaluator;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * @author Gregory Boissinot
 */
public class IvyTrigger extends Trigger<BuildableItem> implements Serializable {

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
            IvyTriggerLog log = new IvyTriggerLog(TaskListener.NULL);
            log.info("Starting to record dependencies versions.");

            AbstractProject abstractProject = (AbstractProject) job;
            Node launcherNode = getLauncherNode(log);
            if (launcherNode == null) {
                log.info("Can't find any complete active node. Checking again in next polling schedule.");
                return;
            }

            IvyTriggerEnvVarsRetriever varsRetriever = new IvyTriggerEnvVarsRetriever();
            Map<String, String> enVars = varsRetriever.getEnvVars(abstractProject, launcherNode, log);

            FilePath ivyFilePath = getDescriptorFilePath(ivyPath, abstractProject, launcherNode, log, enVars);
            if (ivyFilePath == null) {
                log.error(String.format("The ivy file '%s' doesn't exist.", ivyPath));
                return;
            }
            FilePath ivySettingsFilePath = getDescriptorFilePath(ivySettingsPath, abstractProject, launcherNode, log, enVars);

            computedDependencies = getDependenciesMapForNode(launcherNode, log, ivyFilePath, ivySettingsFilePath);
        } catch (IvyTriggerException e) {
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
                                                          IvyTriggerLog log,
                                                          FilePath ivyFilePath,
                                                          FilePath ivySettingsFilePath) throws IOException, InterruptedException, IvyTriggerException {
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

    /**
     * Asynchronous task
     */
    protected class Runner implements Runnable, Serializable {

        private IvyTriggerLog log;

        Runner(IvyTriggerLog log) {
            this.log = log;
        }

        public void run() {

            try {
                long start = System.currentTimeMillis();
                log.info("Polling started on " + DateFormat.getDateTimeInstance().format(new Date(start)));
                boolean changed = checkIfModified(log);
                log.info("Polling complete. Took " + Util.getTimeSpanString(System.currentTimeMillis() - start));
                if (changed) {
                    log.info("Dependencies have changed. Scheduling a build.");
                    job.scheduleBuild(new IvyTriggerCause());
                } else {
                    log.info("No changes.");
                }
            } catch (IvyTriggerException e) {
                log.error("Polling error " + e.getMessage());
            } catch (Throwable e) {
                log.error("SEVERE - Polling error " + e.getMessage());
            }
        }
    }

    private Node getLauncherNode(IvyTriggerLog log) {
        AbstractProject p = (AbstractProject) job;
        Label label = p.getAssignedLabel();
        if (label == null) {
            log.info("Running on master.");
            return getLauncherNodeMaster();
        } else {
            log.info(String.format("Searching a node to run the polling for the label '%s'.", label));
            return getLauncherNodeSlave(p, label, log);
        }
    }

    private Node getLauncherNodeMaster() {
        Computer computer = Hudson.getInstance().toComputer();
        if (computer != null) {
            return computer.getNode();
        } else {
            return null;
        }
    }

    private Node getLauncherNodeSlave(AbstractProject project, Label label, IvyTriggerLog log) {
        Node lastBuildOnNode = project.getLastBuiltOn();
        boolean isAPreviousBuildNode = lastBuildOnNode != null;

        Set<Node> nodes = label.getNodes();
        for (Node node : nodes) {
            if (node != null) {
                if (!isAPreviousBuildNode) {
                    FilePath nodePath = node.getRootPath();
                    if (nodePath != null) {
                        log.info(String.format("Running on %s.", node.getNodeName()));
                        return node;
                    }
                } else {
                    FilePath nodeRootPath = node.getRootPath();
                    if (nodeRootPath != null) {
                        if (nodeRootPath.equals(lastBuildOnNode.getRootPath())) {
                            log.info("Running on " + node.getNodeName());
                            return lastBuildOnNode;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean checkIfModified(IvyTriggerLog log) throws IvyTriggerException, IOException, InterruptedException {

        AbstractProject project = (AbstractProject) job;
        Node launcherNode = getLauncherNode(log);
        if (launcherNode == null) {
            log.info("Can't find any complete active node for the polling action. Maybe slaves are not yet active at this time. Checking again in next polling schedule.");
            return false;
        }

        IvyTriggerEnvVarsRetriever varsRetriever = new IvyTriggerEnvVarsRetriever();
        Map<String, String> envVars = varsRetriever.getEnvVars(project, launcherNode, log);

        //Get ivy file
        FilePath ivyFilePath = getDescriptorFilePath(ivyPath, project, launcherNode, log, envVars);
        if (ivyFilePath == null) {
            log.error(String.format("The ivy file '%s' doesn't exist.", ivyPath));
            return false;
        }

        //Get ivysettings file
        FilePath ivySettingsFilePath = getDescriptorFilePath(ivySettingsPath, project, launcherNode, log, envVars);

        Map<String, String> newComputedDependencies = getDependenciesMapForNode(launcherNode, log, ivyFilePath, ivySettingsFilePath);
        return checkIfModifiedWithResolvedElements(log, newComputedDependencies);
    }

    private boolean checkIfModifiedWithResolvedElements(IvyTriggerLog log, Map<String, String> newComputedDependencies) throws IvyTriggerException {

        if (newComputedDependencies == null) {
            log.error("Can't record the new dependencies graph.");
            computedDependencies = null;
            return false;
        }

        if (newComputedDependencies.size()==0){
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

    private boolean isChangedDependency(IvyTriggerLog log, Map.Entry<String, String> dependency, Map<String, String> newComputedDependencies) {
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
                                           IvyTriggerLog log,
                                           Map<String, String> envVars)
            throws IvyTriggerException {
        try {

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
            throw new IvyTriggerException(ioe);
        } catch (InterruptedException ie) {
            throw new IvyTriggerException(ie);
        }
    }

    /**
     * Gets the triggering log file
     *
     * @return the trigger log
     */
    private File getLogFile() {
        return new File(job.getRootDir(), "ivy-polling.log");
    }

    @Override
    public void run() {

        if (!Hudson.getInstance().isQuietingDown() && ((AbstractProject) this.job).isBuildable()) {
            IvyScriptTriggerDescriptor descriptor = getDescriptor();
            ExecutorService executorService = descriptor.getExecutor();
            StreamTaskListener listener;
            try {
                listener = new StreamTaskListener(getLogFile());
                IvyTriggerLog log = new IvyTriggerLog(listener);
                Runner runner = new Runner(log);
                executorService.execute(runner);

            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "Severe Error during the trigger execution " + t.getMessage());
                t.printStackTrace();
            }
        }
    }


    @Override
    public IvyScriptTriggerDescriptor getDescriptor() {
        return (IvyScriptTriggerDescriptor) Hudson.getInstance().getDescriptorOrDie(getClass());
    }

    @Extension
    @SuppressWarnings("unused")
    public static class IvyScriptTriggerDescriptor extends TriggerDescriptor {

        private transient final SequentialExecutionQueue queue = new SequentialExecutionQueue(Executors.newSingleThreadExecutor());

        public ExecutorService getExecutor() {
            return queue.getExecutors();
        }

        @Override
        public boolean isApplicable(Item item) {
            return true;
        }

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
