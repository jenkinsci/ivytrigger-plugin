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
            computedDependencies = getEvaluatedLatestRevision();
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
                boolean changed = checkIfModified(log);
                log.info(String.format("Computing the dependency of the Ivy path '%s'.", ivyPath));
                log.info("Polling complete. Took " + Util.getTimeSpanString(System.currentTimeMillis() - start));
                if (changed) {
                    log.info("A dependency version has changed. Scheduling a build.");
                    job.scheduleBuild(new IvyTriggerCause());
                } else {
                    log.info("Any dependency version has changed.");
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


    private Map<String, String> getEvaluatedLatestRevision() throws IvyTriggerException {
        Map<String, String> result = new HashMap<String, String>();

        FilePath ivyFileFilePath = null;
        FilePath ivySettingsFilePath = null;

        FilePath oneLauncherNode = getOneLauncherNode();
        try {

            //Try to find the files in the workspace before
            FilePath workspace = ((AbstractProject) job).getSomeWorkspace();
            if (workspace != null) {
                ivyFileFilePath = workspace.child(ivyPath);
                ivySettingsFilePath = workspace.child(ivySettingsPath);
            }

            // Try to find the files on a node (master or slaves)
            if (!ivyFileFilePath.exists() && !ivySettingsFilePath.exists()) {
                ivyFileFilePath = new FilePath(oneLauncherNode, ivyPath);
                ivySettingsFilePath = new FilePath(oneLauncherNode, ivySettingsPath);
            }

            if (!ivyFileFilePath.exists() || !ivySettingsFilePath.exists()) {
                return result;
            }


            final FilePath ivyFilePathFinal = ivyFileFilePath;
            final FilePath ivySettingsFilePathFinal = ivyFileFilePath;

            result = oneLauncherNode.act(new FilePath.FileCallable<Map<String, String>>() {
                public Map<String, String> invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                    Map<String, String> result = new HashMap<String, String>();
                    Ivy ivy = Ivy.newInstance();
                    try {
                        ivy.configure(new File(ivySettingsFilePathFinal.getRemote()));
                        ResolveReport resolveReport = null;
                        resolveReport = ivy.resolve(new File(ivyFilePathFinal.getRemote()));
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
        } catch (IOException ioe) {
            throw new IvyTriggerException(ioe);
        } catch (InterruptedException ie) {
            throw new IvyTriggerException(ie);
        } catch (RuntimeException re) {
            throw new IvyTriggerException(re);
        }


        return result;
    }

    private boolean checkIfModified(IvyTriggerLog log) throws IvyTriggerException {
        Map<String, String> newComputedDependencies = getEvaluatedLatestRevision();
        assert computedDependencies != null;
        assert newComputedDependencies != null;

        if (computedDependencies.size() != newComputedDependencies.size()) {
            log.info(String.format("The dependencies size has changed."));
            return true;
        }

        for (Map.Entry<String, String> dependency : computedDependencies.entrySet()) {

            String moduleId = dependency.getKey();
            String revision = dependency.getKey();
            String newRevision = newComputedDependencies.get(moduleId);
            if (newRevision == null) {
                log.info(String.format("The dependency '%s' doesn't exist anymore.", moduleId));
                return true;
            }

            if (!newRevision.equals(revision)) {
                log.info(String.format("The dependency version '%s' changed. The new version is %s.", moduleId, newRevision));
                return true;
            }

        }

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
            return "Poll with a Ivy script or a Gradle script";
        }
    }


}
