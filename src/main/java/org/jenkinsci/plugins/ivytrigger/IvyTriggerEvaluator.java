package org.jenkinsci.plugins.ivytrigger;

import hudson.FilePath;
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
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;

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

    private boolean debug;

    private Map<String, String> envVars;

    public IvyTriggerEvaluator(String namespace,
                               FilePath ivyFilePath,
                               FilePath ivySettingsFilePath,
                               FilePath propertiesFilePath,
                               String propertiesContent,
                               XTriggerLog log,
                               boolean debug,
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
            Ivy ivy = getIvyObject(launchDir, log);
            log.info("\nResolving Ivy dependencies.");
            ResolveReport resolveReport = ivy.resolve(new File(ivyFilePath.getRemote()));
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

            result = getMapDependencies(ivy, resolveReport, log);

        } catch (ParseException pe) {
            log.error("Parsing error: " + pe.getMessage());
            return null;
        } catch (IOException ioe) {
            log.error("IOException: " + ioe.getMessage());
            return null;
        } catch (XTriggerException xe) {
            log.error("XTrigger exception: " + xe.getMessage());
            return null;
        }

        return result;
    }

    private Ivy getIvyObject(File launchDir, XTriggerLog log) throws XTriggerException {

        Map<String, String> variables = getVariables();

        File tempSettings = null;
        try {

            //------------ENV_VAR_
            StringBuffer envVarsContent = new StringBuffer();
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                envVarsContent.append(String.format("<property name=\"%s\" value=\"%s\"/>\n", entry.getKey(), entry.getValue()));
            }

            //-----------Inject properties files
            String settingsContent = FileUtils.readFileToString(new File(ivySettingsFilePath.getRemote()));
            StringBuffer stringBuffer = new StringBuffer(settingsContent);
            int index = stringBuffer.indexOf("<ivysettings>");
            stringBuffer.insert(index + "<ivysettings>".length() + 1, envVarsContent.toString());
            tempSettings = File.createTempFile("file", ".tmp");
            FileOutputStream fileOutputStream = new FileOutputStream(tempSettings);
            fileOutputStream.write(stringBuffer.toString().getBytes());

            IvySettings ivySettings = new IvySettings();
            ivySettings.load(tempSettings);
            ivySettings.setDefaultCache(getAndInitCacheDir(launchDir));

            Ivy ivy = Ivy.newInstance(ivySettings);
            ivy.getLoggerEngine().pushLogger(new IvyTriggerResolverLog(log, debug));
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                ivy.setVariable(entry.getKey(), entry.getValue());
            }

            return ivy;

        } catch (ParseException pe) {
            throw new XTriggerException(pe);
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        } finally {
            if (tempSettings != null) {
                tempSettings.delete();
            }
        }

    }


    private Map<String, String> getVariables() throws XTriggerException {
        //we want variables to be sorted
        final Map<String, String> variables = new TreeMap<String, String>();
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
        File cacheDir = new File(launchDir, "ivy-trigger-cache/" + namespace);
        cacheDir.mkdirs();
        return cacheDir;
    }


    private Map<String, IvyDependencyValue> getMapDependencies(Ivy ivy, ResolveReport resolveReport, XTriggerLog log) {

        List dependencies = resolveReport.getDependencies();

        Map<String, IvyDependencyValue> result = new HashMap<String, IvyDependencyValue>();
        for (Object dependencyObject : dependencies) {
            try {
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
                                ivyArtifactValues.add(new IvyArtifactValue(artifact.getName(), artifact.getExt(), lastModificationDate));
                            }
                        }
                    }
                }
                result.put(dependencyNode.getId().toString(), new IvyDependencyValue(moduleRevision, ivyArtifactValues));
            } catch (Throwable e) {
                log.error(String.format("Can't retrieve artifacts for dependency" + (IvyNode) dependencyObject));
                continue;
            }
        }

        return result;

    }
}
