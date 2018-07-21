package org.sagebionetworks;

import static org.sagebionetworks.Constants.*;
import static org.sagebionetworks.Constants.SYNAPSE_USERNAME_PROPERTY;
import static org.sagebionetworks.Utils.getProperty;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.evaluation.model.Evaluation;
import org.sagebionetworks.evaluation.model.EvaluationStatus;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.Annotations;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.auth.LoginRequest;
import org.sagebionetworks.repo.model.file.ExternalFileHandle;
import org.sagebionetworks.repo.model.wiki.WikiPage;

public class WorkflowAdmin {

	// if 'TEAR_DOWN_AFTER' is set to false, then use unique names for projects and the evaluation:
	private static final String CHALLENGE_EVALUATION_NAME = "Workflow Queue for project ";

	private SynapseClient synapseAdmin;
	private Archiver archiver ;
	
	enum TASK {
		SET_UP,
		SUBMIT
	};

	// arguments: 
	// SET_UP template-file-path
	// SUBMIT file-path parentID, evaluation queue ID
	public static void main( String[] args ) throws Throwable {
		WorkflowAdmin workflowAdmin = new WorkflowAdmin();
		
		TASK task = TASK.valueOf(args[0]);
		switch(task) {
		case SET_UP:
			// Set Up
			if (args.length!=2) {
				throw new IllegalArgumentException("Expected two param's but found: "+Arrays.asList(args));
			}
			String workflowUrl = getProperty("WORKFLOW_TEMPLATE_URL", false);
			String rootTemplate = getProperty("ROOT_TEMPLATE", false);
			if (StringUtils.isNotEmpty(workflowUrl) && StringUtils.isNotEmpty(rootTemplate)) {
				String projectId = workflowAdmin.createProject();
				String fileEntityId = workflowAdmin.createExternalFileEntity(workflowUrl, projectId, rootTemplate);
				workflowAdmin.setUp(fileEntityId, projectId);
			} else {
				throw new IllegalArgumentException("invalid combination of env var's");
			}
			break;
		case SUBMIT:
			// Create Submission
			if (args.length!=3) throw new IllegalArgumentException("usage: SUBMIT <file path> <evaluation queue ID>");
			workflowAdmin.submit(args[1], args[2]);
			break;
		default:
			throw new IllegalArgumentException("Unexpected task: "+task);
		}

	}

	public WorkflowAdmin() throws SynapseException {
		synapseAdmin = SynapseClientFactory.createSynapseClient();
		String userName = getProperty(SYNAPSE_USERNAME_PROPERTY);
		String password = getProperty(SYNAPSE_PASSWORD_PROPERTY);
		LoginRequest loginRequest = new LoginRequest();
		loginRequest.setUsername(userName);
		loginRequest.setPassword(password);
		synapseAdmin.login(loginRequest);
		archiver = new Archiver(synapseAdmin, null);
	}
	
	public String createFileEntityForFile(String path, String parentId) throws Throwable {
		return archiver.uploadToSynapse(new File(path), parentId);
	}
	
	public String createExternalFileEntity(String externalURL, String parentId, String rootTemplate) throws Throwable {
		ExternalFileHandle efh = new ExternalFileHandle();
		efh.setExternalURL(externalURL);
		efh = synapseAdmin.createExternalFileHandle(efh);
		FileEntity fileEntity = new FileEntity();
		fileEntity.setDataFileHandleId(efh.getId());
		fileEntity.setParentId(parentId);
		fileEntity = synapseAdmin.createEntity(fileEntity);
		Annotations annotations = synapseAdmin.getAnnotations(fileEntity.getId());
		Map<String, List<String>> stringAnnotations = new HashMap<String, List<String>>();
		annotations.setStringAnnotations(stringAnnotations);
		stringAnnotations.put(ROOT_TEMPLATE_ANNOTATION_NAME, Collections.singletonList(rootTemplate));
		annotations = synapseAdmin.updateAnnotations(fileEntity.getId(), annotations);
		return fileEntity.getId();
	}
	
	public String createProject() throws Exception {
		Project project;
		project = new Project();
		project = synapseAdmin.createEntity(project);
		System.out.println("Created "+project.getId());
		{
			AccessControlList acl = synapseAdmin.getACL(project.getId());
			Set<ResourceAccess> ras = acl.getResourceAccess();
			{
				ResourceAccess ra = new ResourceAccess();
				ra.setPrincipalId(Long.parseLong("273948")); // all authenticated users can view and download
				Set<ACCESS_TYPE> accessTypes = new HashSet<ACCESS_TYPE>();
				accessTypes.add(ACCESS_TYPE.DOWNLOAD);
				accessTypes.add(ACCESS_TYPE.CREATE);
				accessTypes.add(ACCESS_TYPE.UPDATE);
				accessTypes.add(ACCESS_TYPE.READ);
				ra.setAccessType(accessTypes);
				ras.add(ra);
			}
			{
				ResourceAccess ra = new ResourceAccess();
				ra.setPrincipalId(Long.parseLong("273949")); // public can view
				Set<ACCESS_TYPE> accessTypes = new HashSet<ACCESS_TYPE>();
				accessTypes.add(ACCESS_TYPE.READ);
				ra.setAccessType(accessTypes);
				ras.add(ra);
			}
		}
		return project.getId();
	}
	
	private static final String DASHBOARD_TEMPLATE =
			"${leaderboard?queryTableResults=true&path=%2Fevaluation%2Fsubmission%2Fquery%3Fquery%3Dselect%2B%2A%2Bfrom%2Bevaluation%5F##EVALUATION_ID##&paging=false&pageSize=100&showRowNumber=false&columnConfig0=none%2CSubmission ID%2CobjectId%3B%2CNONE&columnConfig1=cancelcontrol%2C%2CcancelControl%3B%2CNONE&columnConfig2=none%2CStatus%2Cstatus%3B%2CNONE&columnConfig3=none%2CStatus Details%2CSTATUS%5FDESCRIPTION%3B%2CNONE&columnConfig4=userid%2CUser%2CuserId%3B%2CNONE&columnConfig5=userid%2CUser or Team%2CsubmitterId%3B%2CNONE&columnConfig6=synapseid%2C%2CentityId%3B%2CNONE&columnConfig7=epochdate%2C%2CEXECUTION%5FSTARTED%3B%2CNONE&columnConfig8=epochdate%2C%2CWORKFLOW%5FLAST%5FUPDATED%3B%2CNONE&columnConfig9=synapseid%2CWorkflow Output%2CworkflowOutputFile%3B%2CNONE}";
	
	private static final String EVALUATION_ID_PLACEHOLDER = "##EVALUATION_ID##";
	
	/**
	 * Create the Evaluation queue.
	 * Provide access to participants.
	 * Create a submission dashboard
	 * @throws UnsupportedEncodingException  
	 */
	public void setUp(String fileId, String projectId) throws Throwable {
		Evaluation evaluation;
		// first make sure the objects to be created don't already exist

	
		evaluation = new Evaluation();
		evaluation.setContentSource(projectId);
		evaluation.setName(CHALLENGE_EVALUATION_NAME+projectId);
		evaluation.setStatus(EvaluationStatus.OPEN);
		evaluation.setSubmissionInstructionsMessage("Your workflow submission should be a .cwl parameters file.");
		evaluation.setSubmissionReceiptMessage("Your workflow submission has been received.   Further notifications will be sent by email.");
		evaluation = synapseAdmin.createEvaluation(evaluation);
		{
			AccessControlList acl = synapseAdmin.getEvaluationAcl(evaluation.getId());
			Set<ResourceAccess> ras = acl.getResourceAccess();
			{
				ResourceAccess ra = new ResourceAccess();
				ra.setPrincipalId(Long.parseLong("273948")); // all authenticated users can submit
				Set<ACCESS_TYPE> accessTypes = new HashSet<ACCESS_TYPE>();
				accessTypes.add(ACCESS_TYPE.SUBMIT);
				accessTypes.add(ACCESS_TYPE.READ);
				ra.setAccessType(accessTypes);
				ras.add(ra);
			}
			{
				ResourceAccess ra = new ResourceAccess();
				ra.setPrincipalId(Long.parseLong("273949")); // public can view
				Set<ACCESS_TYPE> accessTypes = new HashSet<ACCESS_TYPE>();
				accessTypes.add(ACCESS_TYPE.READ);
				ra.setAccessType(accessTypes);
				ras.add(ra);
			}
			synapseAdmin.updateEvaluationAcl(acl);
		}
		JSONObject json = new JSONObject();
		json.put(evaluation.getId(), fileId);
		
		// create wiki with submission dashboard
		WikiPage dashboard = new WikiPage();
		String markdown = DASHBOARD_TEMPLATE.replace(EVALUATION_ID_PLACEHOLDER, evaluation.getId());
		dashboard.setMarkdown(markdown);
		synapseAdmin.createWikiPage(projectId, ObjectType.ENTITY, dashboard);
		
		System.out.println("EVALUATION_TEMPLATES: "+json);
	}

	/**
	 * Submit the file to the Evaluation
	 * @throws SynapseException
	 */
	public void submit(String filePath, String evaluationId) throws Throwable {
		Evaluation evaluation = synapseAdmin.getEvaluation(evaluationId);
		String parentId = evaluation.getContentSource();
		String fileId = archiver.uploadToSynapse(new File(filePath), parentId);
		FileEntity fileEntity = synapseAdmin.getEntity(fileId, FileEntity.class);
		Submission submission = new Submission();
		submission.setEntityId(fileId);
		submission.setEvaluationId(evaluationId);
		submission.setVersionNumber(fileEntity.getVersionNumber());
		synapseAdmin.createIndividualSubmission(submission, fileEntity.getEtag(), null, null);
		System.out.println("Submitted "+fileId+" to "+evaluationId);
	}
}
