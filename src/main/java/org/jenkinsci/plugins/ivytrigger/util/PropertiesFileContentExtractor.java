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

    private FilePathFactory filePathFactory;

    public PropertiesFileContentExtractor(FilePathFactory filePathFactory) {
        this.filePathFactory = filePathFactory;
    }

    /**
     * Given a propertiesFilePath value, will split that value into multiple paths, read the content from the resolved file names
     * and return the content.
     * <p/>
     * The content of the property files is assumed to be in properties file format. e.g.:
     * prop1=1
     * prop2=2
     * prop3=3
     * <p/>
     * As an example, if the propertiesFilePath is "a.properties;b.properties", that a.properties contains prop1=2 and that
     * b.properties contains prop2=3, the method will return:
     * prop1=2
     * prop2=3
     *
     * @param propertiesFilePath If this value is empty or null, the method will return an empty string.
     * @return The aggregated content of the properties files
     * @throws XTriggerException
     */
    public String extractPropertiesFileContents(String propertiesFilePath, AbstractProject job, Node pollingNode, XTriggerLog log, Map<String, String> envVars) throws XTriggerException {

        log.info("Given job  properties file path: " + propertiesFilePath);

        String fileContent = "";

        if (StringUtils.isEmpty(propertiesFilePath)) {
            return fileContent;
        }

        List<String> filePaths = splitFilePaths(propertiesFilePath);
        try {
            for (String path : filePaths) {
                FilePath fp = filePathFactory.getDescriptorFilePath(path, job, pollingNode, log, envVars);
                log.info("Resolved properties file value : " + fp.getRemote());
                fileContent += IOUtils.toString(fp.read()) + "\n";
            }
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        }

        return fileContent;
    }


    /**
     * Utility method that:
     * 1) Splits the value on semi-colon. Right now, this is a hard coded value. Could probably be refactored to use a configurable value
     * 2) Trims the values
     * 3) Returns a list of the fixed up values.
     */
    public List<String> splitFilePaths(String propertiesFilePath) {
        List<String> filePathList = new ArrayList<String>();

        String[] paths = StringUtils.split(propertiesFilePath, ";");
        for (String path : paths) {
            String trimmedPath = StringUtils.trim(path);
            filePathList.add(trimmedPath);
        }

        return filePathList;
    }
}
