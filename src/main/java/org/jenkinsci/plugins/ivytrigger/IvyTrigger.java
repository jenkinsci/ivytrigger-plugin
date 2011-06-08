package org.jenkinsci.plugins.ivytrigger;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.*;
import hudson.remoting.VirtualChannel;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.SequentialExecutionQueue;
import hudson.util.StreamTaskListener;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.ResolvedModuleRevision;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.DateFormat;
import java.text.ParseException;
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
            computedDependencies = getEvaluatedLatestRevision(new IvyTriggerLog(TaskListener.NULL));
        } catch (IvyTriggerException e) {
            //Ignore exception, log it
            LOGGER.log(Level.SEVERE, e.getMessage());
        }
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
                log.info(String.format("Checking dependencies version of the Ivy path '%s'.", ivyPath));
                boolean changed = checkIfModified(log);
                log.info("Polling complete. Took " + Util.getTimeSpanString(System.currentTimeMillis() - start));
                if (changed) {
                    log.info("Dependencies have changed. Scheduling a build.");
                    job.scheduleBuild(new IvyTriggerCause());
                } else {
                    log.info("No change.");
                }
            } catch (IvyTriggerException e) {
                log.error("Polling error " + e.getMessage());
            } catch (Throwable e) {
                log.error("SEVERE - Polling error " + e.getMessage());
            }
        }
    }

    private FilePath getOneLauncherNode() {
        AbstractProject p = (AbstractProject) job;
        Label label = p.getAssignedLabel();
        if (label == null) {
            return Hudson.getInstance().getRootPath();
        } else {
            Set<Node> nodes = label.getNodes();
            Node node;
            for (Iterator<Node> it = nodes.iterator(); it.hasNext();) {
                node = it.next();
                FilePath nodePath = node.getRootPath();
                if (nodePath != null) {
                    return nodePath;
                }
            }
            return null;
        }
    }


    private FilePath getDescriptorFilePathIfExists(String path, AbstractProject job, FilePath oneLauncher)
            throws IvyTriggerException, IOException, InterruptedException {

        //1-- Try to find the file in the last workspace if any
        FilePath workspace = job.getSomeWorkspace();
        if (workspace != null) {
            FilePath ivyDescPath = workspace.child(path);
            if (ivyDescPath.exists()) {
                return ivyDescPath;
            }
        }

        //2-- Try Slave

        //A slave is off
        if (oneLauncher == null) {
            //try a full path from the master
            File file = new File(path);
            if (file.exists()) {
                return new FilePath(file);
            }
            return null;
        } else {

            FilePath filePath = new FilePath(oneLauncher, path);
            if (filePath.exists()) {
                return filePath;
            }
            return null;

        }
    }


    private Map<String, String> getEvaluatedLatestRevision(final IvyTriggerLog log) throws IvyTriggerException {

        FilePath oneLauncherNode = getOneLauncherNode();
        try {

            final FilePath ivyFilePath = getDescriptorFilePathIfExists(ivyPath, (AbstractProject) job, oneLauncherNode);
            final FilePath ivySettingsFilePath = getDescriptorFilePathIfExists(ivySettingsPath, (AbstractProject) job, oneLauncherNode);

            if (ivyFilePath == null) {
                log.error(String.format("The ivy file '%s' doesn't exist.", ivyFilePath));
                return null;
            }

            Map<String, String> result = oneLauncherNode.act(new FilePath.FileCallable<Map<String, String>>() {
                public Map<String, String> invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                    Map<String, String> result = new HashMap<String, String>();
                    Ivy ivy = Ivy.newInstance();
                    try {
                        if (ivySettingsFilePath == null) {
                            log.info("Configured Ivy using default 2.0 settings");
                            ivy.configureDefault();
                        } else {
                            log.info("Configured Ivy using the Ivy settings " + ivySettingsFilePath.getRemote());
                            ivy.configure(new File(ivySettingsFilePath.getRemote()));
                        }
                        ResolveReport resolveReport = null;
                        resolveReport = ivy.resolve(new File(ivyFilePath.getRemote()));
                        List dependencies = resolveReport.getDependencies();
                        for (Object dependencyObject : dependencies) {
                            IvyNode dependencyNode = (IvyNode) dependencyObject;
                            ModuleRevisionId moduleRevisionId = dependencyNode.getId();
                            ResolvedModuleRevision resolvedModuleRevision = dependencyNode.getModuleRevision();
                            if (resolvedModuleRevision != null) {
                                String evaluatedRevision = resolvedModuleRevision.getId().getRevision();
                                result.put(moduleRevisionId.toString(), evaluatedRevision);
                            }
                        }
                    } catch (ParseException pe) {
                        throw new RuntimeException(pe);
                    }

                    return result;
                }
            });
            return result;

        } catch (IOException ioe) {
            throw new IvyTriggerException(ioe);
        } catch (InterruptedException ie) {
            throw new IvyTriggerException(ie);
        } catch (RuntimeException re) {
            throw new IvyTriggerException(re);
        }
    }

    private boolean checkIfModified(IvyTriggerLog log) throws IvyTriggerException {

        Map<String, String> newComputedDependencies = getEvaluatedLatestRevision(log);

        if (computedDependencies == null) {
            computedDependencies = newComputedDependencies;
            return false;
        }

        if (newComputedDependencies == null) {
            computedDependencies = null;
            return false;
        }

        if (computedDependencies.size() != newComputedDependencies.size()) {
            log.info(String.format("The dependencies size has changed."));
            computedDependencies = newComputedDependencies;
            return true;
        }

        for (Map.Entry<String, String> dependency : computedDependencies.entrySet()) {

            String moduleId = dependency.getKey();
            String revision = dependency.getValue();
            String newRevision = newComputedDependencies.get(moduleId);
            if (newRevision == null) {
                log.info(String.format("The dependency '%s' doesn't exist anymore.", moduleId));
                computedDependencies = newComputedDependencies;
                return true;
            }

            if (!newRevision.equals(revision)) {
                log.info(String.format("The dependency version '%s' has changed. The new computed version is %s.", moduleId, newRevision));
                computedDependencies = newComputedDependencies;
                return true;
            }

        }

        computedDependencies = newComputedDependencies;
        return false;
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
        public String getDisplayName() {
            return "Poll with an Ivy script";
        }
    }


}
