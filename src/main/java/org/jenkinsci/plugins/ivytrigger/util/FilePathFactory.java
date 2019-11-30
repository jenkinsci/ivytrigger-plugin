package org.jenkinsci.plugins.ivytrigger.util;

import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Node;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class FilePathFactory {

    public FilePath getDescriptorFilePath(String filePath,
                                          AbstractProject job,
                                          Node pollingNode,
                                          XTriggerLog log,
                                          Map<String, String> envVars)
            throws XTriggerException {
        try {
            //If the current file path is not specified, don't compute it
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
            if (pollingNode == null) {
                //try a full path from the master
                File file = new File(resolvedFilePath);
                if (file.exists()) {
                    return new FilePath(file);
                }
                log.error(String.format("Can't find the file '%s'.", resolvedFilePath));
                return null;
            } else {
                FilePath filePathObject = new FilePath(pollingNode.getRootPath(), resolvedFilePath);

                if (filePathObject.exists()) {
                    return filePathObject;
                }

                log.error(String.format("Can't find the file '%s'.", resolvedFilePath));
                return null;
            }

        } catch (IOException | InterruptedException e) {
            throw new XTriggerException(e);
        }
    }
}
