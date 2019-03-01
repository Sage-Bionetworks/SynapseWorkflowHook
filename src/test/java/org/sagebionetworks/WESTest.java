package org.sagebionetworks;

import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.Constants.AGENT_TEMP_DIR_PROPERTY_NAME;
import static org.sagebionetworks.Constants.HOST_TEMP_DIR_PROPERTY_NAME;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WESTest {
	
	@Before
	public void setUp() throws Exception {
		System.setProperty(AGENT_TEMP_DIR_PROPERTY_NAME, System.getProperty("java.io.tmpdir"));
		System.setProperty(HOST_TEMP_DIR_PROPERTY_NAME, System.getProperty("java.io.tmpdir"));
	}
	
	@After
	public void tearDown() throws Exception {
		System.clearProperty(AGENT_TEMP_DIR_PROPERTY_NAME);
		System.clearProperty(HOST_TEMP_DIR_PROPERTY_NAME);
	}

	@Test
	public void testDownloadWorkflowFromURL() throws Exception {
		URL workflowUrl = new URL("https://dockstore.org:8443/api/ga4gh/v2/tools/%23workflow%2Fgithub.com%2Fdenis-yuen%2Fhello-dockstore-workflow%2Fhello-world/versions/1.0/CWL");
		ContainerRelativeFile workflow = WES.downloadWorkflowFromURL(workflowUrl, "Dockstore.cwl");
		File folder = workflow.getContainerPath();
		List<String> workflowFiles = Arrays.asList(folder.list());
		assertTrue(workflowFiles.contains("Dockstore.cwl"));
		assertTrue(workflowFiles.contains("grep.cwl"));
		assertTrue(workflowFiles.contains("wc.cwl"));
	}

}
