package org.jenkinsci.plugins.ivytrigger.util;

import hudson.FilePath;
import hudson.model.AbstractProject;
import hudson.model.Node;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Mike McLean
 */
public class PropertiesFileContentExtractor {

    private final FilePathFactory filePathFactory;

    public PropertiesFileContentExtractor(FilePathFactory filePathFactory) {
        this.filePathFactory = filePathFactory;
    }

    /**
     * Given a propertiesFilePath value, will split that value into multiple paths, read the content from the resolved file names
     * and return the content.
     * <p>
     * The content of the property files is assumed to be in properties file format. e.g.:
     * prop1=1
     * prop2=2
     * prop3=3
     * <p>
     * As an example, if the propertiesFilePath is "a.properties;b.properties", that a.properties contains prop1=2 and that
     * b.properties contains prop2=3, the method will return:
     * prop1=2
     * prop2=3
     *
     * @param propertiesFilePath If this value is empty or null, the method will return an empty string.
     * @param job The job whose workspace is used to resolve the property files.
     * @param pollingNode Jenkins agent used to resolve the property files on. If not provided, Jenkins master is used.
     * @param log Used for logging.
     * @param envVars Environment variables used to resolve in the file paths.
     * @return The aggregated content of the properties files.
     * @throws XTriggerException On error.
     */
    public String extractPropertiesFileContents(String propertiesFilePath, AbstractProject job, Node pollingNode, XTriggerLog log, Map<String, String> envVars) throws XTriggerException {

        log.info("Given job properties file path: " + propertiesFilePath);

        if (StringUtils.isEmpty(propertiesFilePath)) {
            return "";
        }

        StringBuilder fileContent = new StringBuilder();
        List<String> filePaths = splitFilePaths(propertiesFilePath);

        try {
            for (String path : filePaths) {
                FilePath fp = filePathFactory.getDescriptorFilePath(path, job, pollingNode, log, envVars);
                log.info("Resolved properties file value: " + fp.getRemote());
                fileContent.append(IOUtils.toString(fp.read()));
                fileContent.append("\n");
            }
        } catch (IOException | InterruptedException e) {
            throw new XTriggerException(e);
        }

        return fileContent.toString();
    }


    /**
     * Splits the value on semi-colon and trims each path.
     * <p>
     * Right now, the separator is a hard coded value.
     * It could probably be refactored to use a configurable value.
     *
     * @param propertiesFilePath The semi-colon separated value to split.
     * @return The list of paths.
     */
    public List<String> splitFilePaths(String propertiesFilePath) {
        List<String> filePathList = new ArrayList<>();

        String[] paths = StringUtils.split(propertiesFilePath, ";");
        for (String path : paths) {
            String trimmedPath = StringUtils.trim(path);
            filePathList.add(trimmedPath);
        }

        return filePathList;
    }
}
