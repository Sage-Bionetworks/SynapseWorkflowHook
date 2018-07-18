package org.sagebionetworks;

import static org.mockito.Mockito.*;
import static org.mockito.Matchers.*;
import static org.sagebionetworks.Constants.*;
import java.io.File;
import java.io.IOException;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.UserProfile;

@RunWith(MockitoJUnitRunner.class)
public class WorkflowHookTest {
	
	@Mock
	SynapseClient synapse;
	
	@Mock
	EvaluationUtils evaluationUtils;
	
	@Mock
	DockerUtils dockerUtils;
	
	@Mock
	SubmissionUtils submissionUtils;
	
	private WorkflowHook workflowHook;
	
	private static final String USER_ID = "000";
	private static final String EVALUATION_ID = "111";
	private static final String SUBMISSION_ID = "222";
	private static final String FOLDER_ID = "333";
	private static final String WORKFLOW_OUTPUT_ROOT_ENTITY_ID = "syn1234";
	
	private static final File MOCK_TEMPLATE_FOLDER;
	private static final File MOCK_TEMPLATE_FILE;
	private static final FolderAndFile MOCK_TEMPLATE_FOLDER_AND_FILE;
	
	static {
		try {
			MOCK_TEMPLATE_FOLDER = File.createTempFile("foo", "bar", new File(System.getProperty("java.io.tmpdir")));
			MOCK_TEMPLATE_FILE = File.createTempFile("foo", "bar");
			MOCK_TEMPLATE_FOLDER_AND_FILE = new FolderAndFile(MOCK_TEMPLATE_FOLDER, MOCK_TEMPLATE_FILE);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Before
	public void setUp() throws Exception {
		System.setProperty(AGENT_TEMP_DIR_PROPERTY_NAME, System.getProperty("java.io.tmpdir"));
		System.setProperty(HOST_TEMP_DIR_PROPERTY_NAME, System.getProperty("java.io.tmpdir"));
		System.setProperty("WORKFLOW_OUTPUT_ROOT_ENTITY_ID", WORKFLOW_OUTPUT_ROOT_ENTITY_ID);
		System.setProperty("SYNAPSE_USERNAME", "foo");
		System.setProperty("SYNAPSE_PASSWORD", "bar");
		long sleepTimeMillis = 1*60*1000L;
		workflowHook = new WorkflowHook(
				synapse, evaluationUtils,
				dockerUtils, submissionUtils, sleepTimeMillis);

	}
	
	// TODO these tests need to be completed

	@Test
	public void testCreateNewWorkflowJobs() throws Throwable {
		SubmissionBundle bundle = new SubmissionBundle();
		Submission submission = new Submission();
		submission.setId(SUBMISSION_ID);
		submission.setUserId(USER_ID);
		bundle.setSubmission(submission);
		SubmissionStatus submissionStatus = new SubmissionStatus();
		bundle.setSubmissionStatus(submissionStatus);
		when(evaluationUtils.selectSubmissions(EVALUATION_ID, SubmissionStatusEnum.RECEIVED)).thenReturn(Collections.singletonList(bundle));
		UserProfile profile = new UserProfile();
		profile.setOwnerId("1111");
		when(synapse.getMyProfile()).thenReturn(profile);
		Folder folder = new Folder();
		when(synapse.createEntity(any(Folder.class))).thenReturn(folder);
		// method under test
		workflowHook.createNewWorkflowJobs(EVALUATION_ID, MOCK_TEMPLATE_FOLDER_AND_FILE);
		
		
		verify(evaluationUtils).selectSubmissions(EVALUATION_ID, SubmissionStatusEnum.RECEIVED);
	}
	
	@Test 
	public void testUpdateWorkflowJobs() throws Throwable {
		workflowHook.updateWorkflowJobs(EVALUATION_ID);
	}

}
