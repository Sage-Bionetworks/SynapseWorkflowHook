package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.FileUtils.DIRECTORY_CLEANER_REPOSITORY;
import static org.sagebionetworks.FileUtils.DIRECTORY_TO_DELETE_MOUNT;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jersey.repackaged.com.google.common.collect.ImmutableMap;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;

@RunWith(MockitoJUnitRunner.class)
public class FileUtilsTest {
	private static final String HOSTNAME_PROPERTY = "HOSTNAME";
	private static final String HOSTNAME = "HOSTERINO";

	@Mock
	DockerUtils mockDockerUtils;

	@Captor
	ArgumentCaptor<Map<File, String>> fileMapCaptor;

	File directory;

	FileUtils fileUtils;

	String submissionId;


	@BeforeClass
	public static void classSetup(){
		System.setProperty(HOSTNAME_PROPERTY, HOSTNAME);
	}
	@AfterClass
	public static  void classTearDown(){
		System.clearProperty(HOSTNAME_PROPERTY);
	}

	@Before
	public void setUp(){
		directory = new File("/fake/path/");
		fileUtils = new FileUtils(mockDockerUtils);
		submissionId = "011";
	}

	@Test(expected = IllegalArgumentException.class)
	public void testClearDirectoryNullDirectory() throws IOException {
		fileUtils.clearFileDirectory(null, submissionId);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testClearDirectoryNullSubmissionId() throws IOException {
		fileUtils.clearFileDirectory(directory, null);
	}

	@Test
	public void testClearDirectory() throws IOException{
		ContainerState mockState = Mockito.mock(ContainerState.class);
		String containerId = "99jsaif8ufafajfwe9fu";
		when(mockDockerUtils.createModelContainer(eq(DIRECTORY_CLEANER_REPOSITORY), 
				eq(null),
				anyMapOf(File.class, String.class), anyMapOf(File.class, String.class), any(List.class), 
				eq(Arrays.asList(DIRECTORY_TO_DELETE_MOUNT)), eq(null))).thenReturn(containerId);

		when(mockDockerUtils.getContainerState(containerId)).thenReturn(mockState);
		when(mockState.getRunning()).thenReturn(true, false);

		//method under test
		fileUtils.clearFileDirectory(directory, submissionId);


		verify(mockDockerUtils).createModelContainer(eq(DIRECTORY_CLEANER_REPOSITORY),
				eq(null), anyMapOf(File.class, String.class), fileMapCaptor.capture(),
				any(List.class), eq(Arrays.asList(DIRECTORY_TO_DELETE_MOUNT)), eq(null));
		verify(mockDockerUtils).removeContainer(containerId, true);

		assertEquals(ImmutableMap.of(directory, DIRECTORY_TO_DELETE_MOUNT), fileMapCaptor.getValue());
	}


}
