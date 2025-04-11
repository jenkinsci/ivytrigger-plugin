package org.jenkinsci.plugins.ivytrigger.util;

import hudson.FilePath;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.xtriggerapi.XTriggerLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PropertiesFileContentExtractorTest {

    private PropertiesFileContentExtractor propertiesFileContentExtractor;
    private FilePathFactory filePathFactory;

    private XTriggerLog log;
    private Map<String, String> envVars;

    @BeforeEach
    void setUp() {
        filePathFactory = mock(FilePathFactory.class);
        propertiesFileContentExtractor = new PropertiesFileContentExtractor(filePathFactory);
        envVars = new HashMap<>();
        log = new XTriggerLog(TaskListener.NULL);
    }

    @Test
    void getContent_withEmptyPropertiesPath() throws Exception {
        String content = propertiesFileContentExtractor.extractPropertiesFileContents("", null, null, log, envVars);

        assertEquals("", content);
    }

    @Test
    void getContent_withNullPropertiesPath() throws Exception {
        String content = propertiesFileContentExtractor.extractPropertiesFileContents(null, null, null, log, envVars);

        assertEquals("", content);
    }

    @Test
    void getContent_WithContentSingleFilePath() throws Exception {
        FilePath filePath1 = mock(FilePath.class);
        FilePath filePath2 = mock(FilePath.class);

        when(filePath1.read()).thenReturn(stringToInputStream("1=one\n2=two"));
        when(filePath2.read()).thenReturn(stringToInputStream("3=three\n4=four"));

        when(filePathFactory.getDescriptorFilePath("a/", null, null, log, envVars)).thenReturn(filePath1);
        when(filePathFactory.getDescriptorFilePath("b/", null, null, log, envVars)).thenReturn(filePath2);

        String content = propertiesFileContentExtractor.extractPropertiesFileContents("a/;b/", null, null, log, envVars);

        assertEquals("1=one\n2=two\n3=three\n4=four\n", content);
    }

    @Test
    void splitFilePaths_WithSingleValue() {
        List<String> filePaths = propertiesFileContentExtractor.splitFilePaths("abcd/");

        assertEquals(1, filePaths.size());
        assertEquals("abcd/", filePaths.get(0));
    }

    @Test
    void splitFilePaths_WithMultipleValues() {
        List<String> filePaths = propertiesFileContentExtractor.splitFilePaths("abcd/;efgh/");

        assertEquals(2, filePaths.size());
        assertEquals("abcd/", filePaths.get(0));
        assertEquals("efgh/", filePaths.get(1));
    }

    @Test
    void splitFilePaths_WithMultipleValues_Trim() {
        List<String> filePaths = propertiesFileContentExtractor.splitFilePaths(" /abcd/ ; /efgh");

        assertEquals(2, filePaths.size());
        assertEquals("/abcd/", filePaths.get(0));
        assertEquals("/efgh", filePaths.get(1));
    }

    private InputStream stringToInputStream(String props) {
        return new ByteArrayInputStream(props.getBytes());
    }
}
