package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.SubmissionUtils.getRepoSuffixFromImage;
import static org.sagebionetworks.SubmissionUtils.getSynapseProjectIdForDockerImage;
import static org.sagebionetworks.SubmissionUtils.validateDockerCommit;
import static org.sagebionetworks.SubmissionUtils.validateSynapseId;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseForbiddenException;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.EntityBundle;
import org.sagebionetworks.repo.model.EntityHeader;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.TeamMember;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;


@RunWith(MockitoJUnitRunner.class)
public class SubmissionUtilsTest {

	private static final String VALID_COMMIT = "docker.synapse.org/syn123/my-repo@sha256:9e25a2dcbacacab7777095e497bd45286d53656440a72224d52d09fa3da3c8d3";

	private File fileToDelete;

	@Mock
	SynapseClient synapse;


	@After 
	public void tearDown() throws Exception {
		if (fileToDelete!=null) fileToDelete.delete();
		fileToDelete = null;
	}

	@Test
	public void testValidateDockerCommitHappyCase() throws Exception {
		validateDockerCommit(VALID_COMMIT);
	}

	@Test
	public void testValidateDockerCommitHappyCase2() throws Exception {
		validateDockerCommit("docker.synapse.org/syn5644795/dm-python-training-example@sha256:da27f973e4a85ceb59d8d79f3a50ef6a804ab2d3c00a5c1d26ec9e7893a2241f");
	}

	@Test(expected=InvalidSubmissionException.class)
	public void testValidateDockerCommitBlackHole() throws Exception {
		validateDockerCommit("docker.synapse.org/syn5644795/dm-python-training-examplesha256:da27f973e4a85ceb59d8d79f3a50ef6a804ab2d3c00a5c1d26ec9e7893a2241f");
	}

	@Test(expected=InvalidSubmissionException.class)
	public void testValidateDockerCommitTrailingSlash() throws Exception {
		validateDockerCommit("docker.synapse.org/syn8119917/preprocess-reducedata-keras/@sha256:01c9ae894afefca9ce7e543d3fc4576fc750a4b3f813f7e03954e1cbd46ee5b0");
	}

	@Test
	public void testValidateDockerCommitDockerhubRepo() throws Exception {
		validateDockerCommit("dockerhubuser/my-repo@sha256:9e25a2dcbacacab7777095e497bd45286d53656440a72224d52d09fa3da3c8d3");
	}

	@Test(expected=InvalidSubmissionException.class)
	public void testValidateDockerCommitWithLabel() throws Exception {
		validateDockerCommit("docker.synapse.org/syn123/my-repo:latest");
	}

	@Test(expected=InvalidSubmissionException.class)
	public void testValidateDockerCommitNoCommit() throws Exception {
		validateDockerCommit("docker.synapse.org/syn123/my-repo");
	}

	private static InputStream stringToInputStream(String s) throws IOException {
		return new ByteArrayInputStream(s.getBytes());
	}

	@Test
	public void testSynIDCommitHappyCase() throws Exception {
		validateSynapseId("syn1234");
		validateSynapseId(" syn1234 ");
		validateSynapseId("SYN1234");
	}

	@Test(expected=InvalidSubmissionException.class)
	public void testSynIDinvalidNoNumber() throws Exception {
		validateSynapseId("syn");
	}

	@Test(expected=InvalidSubmissionException.class)
	public void testSynIDinvalidNoSyn() throws Exception {
		validateSynapseId("123456");
	}


	@Test
	public void testGetSynapseProjectIdForDockerImage() throws Exception {
		// happy case
		assertEquals("syn123", getSynapseProjectIdForDockerImage(VALID_COMMIT));
		// a DockerHub image
		assertNull(getSynapseProjectIdForDockerImage("username/ubuntu@sha256:9e25a2dcbacacab7777095e497bd45286d53656440a72224d52d09fa3da3c8d3"));
		// not a Synapse Docker image
		assertNull(getSynapseProjectIdForDockerImage("quay.io/syn123/repo@sha256:9e25a2dcbacacab7777095e497bd45286d53656440a72224d52d09fa3da3c8d3"));
	}

	@Test(expected=InvalidSubmissionException.class)
	public void testGetSynapseProjectIdForDockerImageNoProjectId() throws Exception {
		getSynapseProjectIdForDockerImage("docker.synapse.org/uname/my-repo@sha256:badb5fed0bd46765002291063bb259acff13c2785ce26b04de842b0de70d01c4");
	}

	@Test 
	public void testGetRepoSuffixFromImage() throws Exception {
		assertEquals("foo/bar", getRepoSuffixFromImage("docker.synapse.org/syn12345/foo/bar@sha256:abcdefg"));
		assertEquals("foo/bar", getRepoSuffixFromImage("docker.synapse.org/syn12345/foo/bar"));
		assertEquals("foo", getRepoSuffixFromImage("docker.synapse.org/syn12345/foo@sha256:abcdefg"));
		assertEquals("ubuntu", getRepoSuffixFromImage("ubuntu@sha256:abcdefg"));
		assertEquals("foo/bar", getRepoSuffixFromImage("syn12345/foo/bar@sha256:abcdefg"));
		assertEquals("foo/bar", getRepoSuffixFromImage("docker.synapse.org/foo/bar@sha256:abcdefg"));
	}

	@Test
	public void testGetEntityTypeFromSubmission() throws Exception {
		Submission submission = new Submission();
		EntityBundle bundle = new EntityBundle();
		FileEntity fileEntity = new FileEntity();
		bundle.setEntity(fileEntity);
		JSONObjectAdapter joa = new JSONObjectAdapterImpl();
		bundle.writeToJSONObject(joa);
		submission.setEntityBundleJSON(joa.toJSONString());
		assertEquals(FileEntity.class, SubmissionUtils.getEntityTypeFromSubmission(submission));
	}

	private static final String ENTITY_ID="syn123";
	private static final String USER_ID = "45678";
	private static final String SUBMISSION_ID = "987654";

	// this is the case where a user is granted READ access explicitly to the entity
	@Test
	public void testValidateEntityAccess() throws Exception {
		EntityHeader entityHeader = new EntityHeader();
		entityHeader.setId(ENTITY_ID);
		when(synapse.getEntityBenefactor(ENTITY_ID)).thenReturn(entityHeader);
		Collection<String> contributors = Collections.singletonList(USER_ID);
		SubmissionUtils submissionUtils = new SubmissionUtils(synapse);
		AccessControlList acl = new AccessControlList();
		ResourceAccess ra = new ResourceAccess();
		ra.setPrincipalId(Long.parseLong(USER_ID));
		ra.setAccessType(Collections.singleton(ACCESS_TYPE.READ));
		acl.setResourceAccess(Collections.singleton(ra));
		when(synapse.getACL(ENTITY_ID)).thenReturn(acl);

		PaginatedResults<TeamMember> pgtm = new PaginatedResults<TeamMember>();
		pgtm.setTotalNumberOfResults(0L);
		when(synapse.getTeamMembers(USER_ID, null, 50, 0L)).thenReturn(pgtm);

		// method under test
		submissionUtils.validateEntityAccessGivenEntityId(ENTITY_ID, contributors);
	}

	// this is the case where the PUBLIC is granted READ access to the entity
	@Test
	public void testValidateEntityAccessPUBLIC() throws Exception {
		EntityHeader entityHeader = new EntityHeader();
		entityHeader.setId(ENTITY_ID);
		when(synapse.getEntityBenefactor(ENTITY_ID)).thenReturn(entityHeader);
		Collection<String> contributors = Collections.singletonList(USER_ID);
		SubmissionUtils submissionUtils = new SubmissionUtils(synapse);
		AccessControlList acl = new AccessControlList();
		ResourceAccess ra = new ResourceAccess();
		ra.setPrincipalId(273949L);
		ra.setAccessType(Collections.singleton(ACCESS_TYPE.READ));
		acl.setResourceAccess(Collections.singleton(ra));
		when(synapse.getACL(ENTITY_ID)).thenReturn(acl);

		PaginatedResults<TeamMember> pgtm = new PaginatedResults<TeamMember>();
		pgtm.setTotalNumberOfResults(0L);
		when(synapse.getTeamMembers("273949", null, 50, 0L)).thenReturn(pgtm);

		// method under test
		submissionUtils.validateEntityAccessGivenEntityId(ENTITY_ID, contributors);
	}

	// test the case in which we can't access the entity
	@Test(expected=InvalidSubmissionException.class)
	public void testValidateEntityAccessForbidden() throws Exception {
		when(synapse.getEntityBenefactor(ENTITY_ID)).thenThrow(new SynapseForbiddenException());
		SubmissionUtils submissionUtils = new SubmissionUtils(synapse);

		// method under test
		submissionUtils.validateEntityAccessGivenEntityId(ENTITY_ID, Collections.singletonList(USER_ID));

	}
	
	@Test
	public void testUpdateSubmissionStatus() throws Exception {
		SubmissionUtils submissionUtils = new SubmissionUtils(synapse);
		
		SubmissionStatus submissionStatus = new SubmissionStatus();
		when(synapse.getSubmissionStatus(SUBMISSION_ID)).thenReturn(submissionStatus);
		
		when (synapse.updateSubmissionStatus(any(SubmissionStatus.class))).thenReturn(submissionStatus);

		SubmissionStatusModifications statusMods = new SubmissionStatusModifications();
		
		// method under test
		submissionUtils.updateSubmissionStatus(submissionStatus, statusMods);
	}

}
