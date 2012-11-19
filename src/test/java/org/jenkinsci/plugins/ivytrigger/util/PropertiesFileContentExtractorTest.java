package org.jenkinsci.plugins.ivytrigger.util;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import hudson.FilePath;
import hudson.model.FreeStyleProject;
import hudson.model.Node;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jenkinsci.lib.xtrigger.XTriggerLog;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({FilePath.class})
public class PropertiesFileContentExtractorTest {
	private PropertiesFileContentExtractor propertiesFileContentExtractor;
	private FilePathFactory filePathFactory;
	
	private FreeStyleProject job;
	private Node pollingNode;
	private XTriggerLog log;
	private Map<String, String> envVars;
	
	@Before
	public void setUp() throws Exception {
		this.filePathFactory = mock(FilePathFactory.class);
		this.propertiesFileContentExtractor = new PropertiesFileContentExtractor(filePathFactory);
		this.envVars = new HashMap<String, String>();
		this.log = new XTriggerLog(null); // XTriggerLog can deal with the null listener, so good enough.
	}
	
	@After
	public void tearDown() {
		this.propertiesFileContentExtractor = null;
		this.filePathFactory = null;
		this.envVars = null;
		this.log = null;;
	}
	
	@Test
	public void getContent_withEmptyPropertiesPath() throws Exception {
		String content = propertiesFileContentExtractor.extractPropertiesFileContents(null, job, pollingNode, log, envVars);
		
		Assert.assertEquals("", content);
	}

	@Test
	public void getContent_withNullPropertiesPath() throws Exception {
		String content = propertiesFileContentExtractor.extractPropertiesFileContents(null, job, pollingNode, log, envVars);
		
		Assert.assertEquals("", content);
	}

	@Test
	public void getContent_WithContentSingleFilePath() throws Exception {
		FilePath filePath1 = PowerMockito.mock(FilePath.class);
		FilePath filePath2 = PowerMockito.mock(FilePath.class);
		
		when(filePath1.read()).thenReturn(stringToInputStream("1=one\n2=two"));
		when(filePath2.read()).thenReturn(stringToInputStream("3=three\n4=four"));
		
		when(filePathFactory.getDescriptorFilePath("a/", job, pollingNode, log, envVars)).thenReturn(filePath1);
		when(filePathFactory.getDescriptorFilePath("b/", job, pollingNode, log, envVars)).thenReturn(filePath2);
		
		String content = this.propertiesFileContentExtractor.extractPropertiesFileContents("a/;b/", job, pollingNode, log, envVars);
		
		Assert.assertEquals("1=one\n2=two\n3=three\n4=four\n", content);
	}

	@Test
	public void splitFilePaths_WithSingleValue() throws Exception {
		List<String> filePaths = propertiesFileContentExtractor.splitFilePaths("abcd/");
		
		Assert.assertEquals(1, filePaths.size());
		Assert.assertEquals("abcd/", filePaths.get(0));
	}
	
	@Test
	public void splitFilePaths_WithMultipleValues() throws Exception {
		List<String> filePaths = propertiesFileContentExtractor.splitFilePaths("abcd/;efgh/");
		
		Assert.assertEquals(2, filePaths.size());
		Assert.assertEquals("abcd/", filePaths.get(0));
		Assert.assertEquals("efgh/", filePaths.get(1));
	}
	
	@Test
	public void splitFilePaths_WithMultipleValues_Trim() throws Exception {
		List<String> filePaths = propertiesFileContentExtractor.splitFilePaths(" /abcd/ ; /efgh");
		
		Assert.assertEquals(2, filePaths.size());
		Assert.assertEquals("/abcd/", filePaths.get(0));
		Assert.assertEquals("/efgh", filePaths.get(1));	
	}
	
	private InputStream stringToInputStream(String props) {
		return new ByteArrayInputStream(props.getBytes());
	}
}
