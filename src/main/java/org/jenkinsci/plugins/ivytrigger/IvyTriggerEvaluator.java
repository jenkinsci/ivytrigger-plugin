package org.jenkinsci.plugins.ivytrigger;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;

import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.cache.ArtifactOrigin;
import org.apache.ivy.core.cache.DefaultRepositoryCacheManager;
import org.apache.ivy.core.cache.RepositoryCacheManager;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.core.settings.IvyVariableContainer;
import org.apache.ivy.core.settings.IvyVariableContainerImpl;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;

import java.io.*;
import java.net.URL;
import java.text.ParseException;
import java.util.*;

/**
 * @author Gregory Boissinot
 */
public class IvyTriggerEvaluator extends MasterToSlaveFileCallable<Map<String, IvyDependencyValue>> {

    private final String namespace;

    private final FilePath ivyFilePath;

    private final FilePath ivySettingsFilePath;

    private final URL ivySettingsURL;

    private final FilePath propertiesFilePath;

    private final String propertiesContent;

    private final XTriggerLog log;

    private final boolean debug;

    private final boolean downloadArtifacts;

    private final Map<String, String> envVars;

    public IvyTriggerEvaluator(String namespace,
                               FilePath ivyFilePath,
                               FilePath ivySettingsFilePath,
                               URL ivySettingsURL,
                               FilePath propertiesFilePath,
                               String propertiesContent,
                               XTriggerLog log,
                               boolean debug,
                               boolean downloadArtifacts,
                               Map<String, String> envVars) {
        this.namespace = namespace;
        this.ivyFilePath = ivyFilePath;
        this.ivySettingsFilePath = ivySettingsFilePath;
        this.ivySettingsURL = ivySettingsURL;
        this.propertiesFilePath = propertiesFilePath;
        this.propertiesContent = propertiesContent;
        this.log = log;
        this.debug = debug;
        this.downloadArtifacts = downloadArtifacts;
        this.envVars = envVars;
    }

    @Override
    public Map<String, IvyDependencyValue> invoke(File launchDir, VirtualChannel channel) throws IOException, InterruptedException {
        try {
            Ivy ivy = getIvyObject(launchDir, log);
            log.info("\nResolving Ivy dependencies.");

            ResolveOptions options = new ResolveOptions();
            options.setDownload(downloadArtifacts);

            File ivyFile = new File(ivyFilePath.getRemote());

            ResolveReport resolveReport = ivy.resolve(ivyFile, options);
            if (resolveReport.hasError()) {
                List problems = resolveReport.getAllProblemMessages();
                if (problems != null && !problems.isEmpty()) {
                    StringBuilder errorMsgs = new StringBuilder();
                    errorMsgs.append("Errors:\n");
                    for (Object problem : problems) {
                        errorMsgs.append(problem);
                        errorMsgs.append("\n");
                    }
                    log.error(errorMsgs.toString());
                }
            }

            return getMapDependencies(ivy, resolveReport, log);

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
    }

    private Ivy getIvyObject(File launchDir, XTriggerLog log) throws XTriggerException {
        File tempSettingsFile = null;
        try {
            IvyVariableContainer variables = new IvyVariableContainerImpl(getVariables());

            String settingsContent = getIvySettingsContents();
            tempSettingsFile = File.createTempFile("file", ".tmp");
            FileUtils.write(tempSettingsFile, settingsContent);

            IvySettings ivySettings = new IvySettings(variables);
            ivySettings.load(tempSettingsFile);
            ivySettings.setDefaultCache(getAndInitCacheDir(launchDir));

            Ivy ivy = Ivy.newInstance(ivySettings);
            ivy.getLoggerEngine().pushLogger(new IvyTriggerResolverLog(log, debug));

            return ivy;

        } catch (ParseException pe) {
            throw new XTriggerException(pe);
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        } finally {
            if (tempSettingsFile != null) {
                if (!tempSettingsFile.delete()) {
                    log.error("Can't delete temporary file: " + tempSettingsFile);
                }
            }
        }
    }

    /**
     * Method retrieves Ivy Settings contents from URL or from file on
     * master/slave
     * @throws IOException on some IO exception occurs
     */
    private String getIvySettingsContents() throws IOException {
        if (ivySettingsFilePath != null) {
            log.info("Getting settings from file " + ivySettingsFilePath.getRemote());
            return FileUtils.readFileToString(new File(ivySettingsFilePath.getRemote()));
        } else {
            log.info("Getting settings from URL " + ivySettingsURL.toString());
            InputStream is = null;
            try {
                log.info("Getting settings from URL");
                is = ivySettingsURL.openStream();
                return IOUtils.toString(is);
            } finally {
                if ( is != null ) {
                    is.close();
                }
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

                propertiesFilePath.act(new MasterToSlaveFileCallable<Void>() {
                    @Override
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

                List<IvyArtifactValue> ivyArtifactValues = new ArrayList<IvyArtifactValue>();

                if ( dependencyNode.isDownloaded() ) {
                    Artifact[] artifacts = dependencyNode.getAllArtifacts();

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
                }
                result.put(dependencyNode.getId().toString(), new IvyDependencyValue(moduleRevision, ivyArtifactValues));
            } catch (Throwable e) {
                log.error("Can't retrieve artifacts for dependency" + (IvyNode) dependencyObject);
            }
        }

        return result;

    }
}
