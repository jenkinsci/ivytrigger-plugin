package org.jenkinsci.plugins.ivytrigger.service;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.cache.ArtifactOrigin;
import org.apache.ivy.core.cache.DefaultRepositoryCacheManager;
import org.apache.ivy.core.cache.RepositoryCacheManager;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.util.DefaultMessageLogger;
import org.apache.ivy.util.Message;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;
import org.jenkinsci.plugins.ivytrigger.IvyArtifactValue;
import org.jenkinsci.plugins.ivytrigger.IvyDependencyValue;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.text.ParseException;
import java.util.*;

/**
 * @author Gregory Boissinot
 */
public class IvyTriggerEvaluator implements FilePath.FileCallable<Map<String, IvyDependencyValue>> {

    private String namespace;

    private FilePath ivyFilePath;

    private FilePath ivySettingsFilePath;

    private FilePath propertiesFilePath;

    private String propertiesContent;

    private XTriggerLog log;

    private Map<String, String> envVars;

    public IvyTriggerEvaluator(String namespace,
                               FilePath ivyFilePath,
                               FilePath ivySettingsFilePath,
                               FilePath propertiesFilePath,
                               String propertiesContent,
                               XTriggerLog log,
                               Map<String, String> envVars) {
        this.namespace = namespace;
        this.ivyFilePath = ivyFilePath;
        this.ivySettingsFilePath = ivySettingsFilePath;
        this.propertiesFilePath = propertiesFilePath;
        this.propertiesContent = propertiesContent;
        this.log = log;
        this.envVars = envVars;
    }

    public Map<String, IvyDependencyValue> invoke(File launchDir, VirtualChannel channel) throws IOException, InterruptedException {
        Map<String, IvyDependencyValue> result;
        try {
            log.info("\nResolving Ivy dependencies.");
            log.info(String.format("Ivy path: %s", ivyFilePath));
            ResolveReport resolveReport;
            Ivy ivy = getIvyObject(launchDir, log);
            ivy.getLoggerEngine().pushLogger(new DefaultMessageLogger(Message.MSG_VERBOSE));
            resolveReport = ivy.resolve(new File(ivyFilePath.getRemote()));
            if (resolveReport.hasError()) {
                List problems = resolveReport.getAllProblemMessages();
                if (problems != null && !problems.isEmpty()) {
                    StringBuffer errorMsgs = new StringBuffer();
                    errorMsgs.append("Errors:\n");
                    for (Object problem : problems) {
                        errorMsgs.append(problem);
                        errorMsgs.append("\n");
                    }
                    log.error(errorMsgs.toString());
                }
            }

            result = getMapDependencies(ivy, resolveReport);

        } catch (ParseException pe) {
            throw new IOException(pe);
        } catch (IOException ioe) {
            throw new IOException(ioe);
        } catch (XTriggerException xe) {
            throw new IOException(xe);
        }

        return result;
    }

    private Ivy getIvyObject(File launchDir, XTriggerLog log) throws XTriggerException {

        final Ivy ivy = Ivy.newInstance();
        try {

            if (ivySettingsFilePath == null) {
                log.info("Ivy settings: default 2.0 settings");
                ivy.configureDefault();
            } else {
                log.info(String.format("Ivy settings: %s", ivySettingsFilePath.getRemote()));
                ivy.configure(new File(ivySettingsFilePath.getRemote()));
            }

            IvySettings ivySettings = ivy.getSettings();
            ivySettings.setDefaultCache(getAndInitCacheDir(launchDir));

            //Inject variables from dependencies properties and envVars
            if (envVars != null) {
                for (Map.Entry<String, String> entryVar : envVars.entrySet()) {
                    ivy.setVariable(entryVar.getKey(), entryVar.getValue());
                }
            }

            if (propertiesFilePath != null) {
                try {
                    propertiesFilePath.act(new FilePath.FileCallable<Void>() {
                        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                            Properties properties = new Properties();
                            FileReader fileReader = new FileReader(propertiesFilePath.getRemote());
                            properties.load(fileReader);
                            fileReader.close();
                            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                                ivy.setVariable(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                            }
                            return null;
                        }
                    }
                    );
                } catch (InterruptedException e) {
                    throw new XTriggerException(e);
                }
            }

            if (propertiesContent != null) {
                Properties properties = new Properties();
                StringReader stringReader = new StringReader(propertiesContent);
                properties.load(stringReader);
                stringReader.close();
                for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                    ivy.setVariable(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        } catch (ParseException pe) {
            throw new XTriggerException(pe);
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        }
        return ivy;
    }


    private File getAndInitCacheDir(File launchDir) {
        File cacheDir = new File(launchDir, "cache/" + namespace);
        cacheDir.mkdirs();
        return cacheDir;
    }


    private Map<String, IvyDependencyValue> getMapDependencies(Ivy ivy, ResolveReport resolveReport) {
        List dependencies = resolveReport.getDependencies();
        Map<String, IvyDependencyValue> result = new HashMap<String, IvyDependencyValue>();
        for (Object dependencyObject : dependencies) {
            IvyNode dependencyNode = (IvyNode) dependencyObject;
            ModuleRevisionId moduleRevisionId = dependencyNode.getResolvedId();
            String moduleRevision = moduleRevisionId.getRevision();
            Artifact[] artifacts = dependencyNode.getAllArtifacts();
            List<IvyArtifactValue> ivyArtifactValues = new ArrayList<IvyArtifactValue>();
            if (artifacts != null) {
                for (Artifact artifact : artifacts) {
                    IvySettings settings = ivy.getSettings();
                    File cacheDirFile = settings.getDefaultRepositoryCacheBasedir();
                    RepositoryCacheManager repositoryCacheManager = new DefaultRepositoryCacheManager("repo", settings, cacheDirFile);
                    ArtifactOrigin artifactOrigin = repositoryCacheManager.getSavedArtifactOrigin(artifact);
                    if (artifactOrigin != null && artifactOrigin.isLocal()) {
                        String location = artifactOrigin.getLocation();
                        File artifactFile = new File(location);
                        if (artifactFile != null) {
                            long lastModificationDate = artifactFile.lastModified();
                            ivyArtifactValues.add(new IvyArtifactValue(artifact.getName(), lastModificationDate));
                        }
                    }
                }
            }
            result.put(moduleRevisionId.toString(), new IvyDependencyValue(moduleRevision, ivyArtifactValues));
        }

        return result;
    }

}
