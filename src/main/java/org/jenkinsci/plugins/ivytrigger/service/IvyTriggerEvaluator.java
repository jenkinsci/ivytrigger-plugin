package org.jenkinsci.plugins.ivytrigger.service;

import hudson.FilePath;
import hudson.Util;
import hudson.remoting.VirtualChannel;
import org.apache.commons.io.FileUtils;
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

import java.io.*;
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

        Map<String, String> variables = getVariables();

        final Ivy ivy = Ivy.newInstance();
        try {

            if (ivySettingsFilePath == null) {
                log.info("Ivy settings: default 2.0 settings");
                ivy.configureDefault();
            } else {
                log.info(String.format("Ivy settings: %s", ivySettingsFilePath.getRemote()));
                String settingsContent = FileUtils.readFileToString(new File(ivySettingsFilePath.getRemote()));
                String settingsContentResolved = Util.replaceMacro(settingsContent, variables);
                File tempSettings = File.createTempFile("file", ".tmp");
                BufferedWriter out = new BufferedWriter(new FileWriter(tempSettings));
                out.write(settingsContentResolved);
                out.close();
                ivy.configure(tempSettings);
                tempSettings.delete();
            }

            IvySettings ivySettings = ivy.getSettings();
            ivySettings.setDefaultCache(getAndInitCacheDir(launchDir));

            for (Map.Entry<String, String> entry : variables.entrySet()) {
                ivy.setVariable(entry.getKey(), entry.getValue());
            }
            ivySettings.addAllVariables(variables);


        } catch (ParseException pe) {
            throw new XTriggerException(pe);
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        }
        return ivy;
    }


    private Map<String, String> getVariables() throws XTriggerException {
        final Map<String, String> variables = new HashMap<String, String>();
        try {

            //Inject variables from dependencies properties and envVars
            if (envVars != null) {
                variables.putAll(envVars);
            }

            if (propertiesFilePath != null) {

                propertiesFilePath.act(new FilePath.FileCallable<Void>() {
                    public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                        Properties properties = new Properties();
                        FileReader fileReader = new FileReader(propertiesFilePath.getRemote());
                        properties.load(fileReader);
                        fileReader.close();
                        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                            variables.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                        }
                        return null;
                    }
                }
                );
            }

            if (propertiesContent != null) {
                Properties properties = new Properties();
                StringReader stringReader = new StringReader(propertiesContent);
                properties.load(stringReader);
                stringReader.close();
                for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                    variables.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }

        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        } catch (InterruptedException ie) {
            throw new XTriggerException(ie);
        }

        return variables;
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
