package org.sagebionetworks;

import static org.sagebionetworks.Constants.MAX_LOG_ANNOTATION_CHARS;
import static org.sagebionetworks.Constants.NOTIFICATION_PRINCIPAL_ID;
import static org.sagebionetworks.Constants.ROOT_TEMPLATE_ANNOTATION_NAME;
import static org.sagebionetworks.Constants.SYNAPSE_PASSWORD_PROPERTY;
import static org.sagebionetworks.Constants.SYNAPSE_USERNAME_PROPERTY;
import static org.sagebionetworks.DockerUtils.PROCESS_TERMINATED_ERROR_CODE;
import static org.sagebionetworks.EvaluationUtils.ADMIN_ANNOTS_ARE_PRIVATE;
import static org.sagebionetworks.EvaluationUtils.FAILURE_REASON;
import static org.sagebionetworks.EvaluationUtils.JOB_LAST_UPDATED_TIME_STAMP;
import static org.sagebionetworks.EvaluationUtils.JOB_STARTED_TIME_STAMP;
import static org.sagebionetworks.EvaluationUtils.LAST_LOG_UPLOAD;
import static org.sagebionetworks.EvaluationUtils.LOG_FILE_NOTIFICATION_SENT;
import static org.sagebionetworks.EvaluationUtils.LOG_FILE_SIZE_EXCEEDED;
import static org.sagebionetworks.EvaluationUtils.PROGRESS;
import static org.sagebionetworks.EvaluationUtils.PUBLIC_ANNOTATION_SETTING;
import static org.sagebionetworks.EvaluationUtils.STATUS_DESCRIPTION;
import static org.sagebionetworks.EvaluationUtils.SUBMISSION_ARTIFACTS_FOLDER;
import static org.sagebionetworks.EvaluationUtils.getFinalSubmissionState;
import static org.sagebionetworks.EvaluationUtils.getInProgressSubmissionState;
import static org.sagebionetworks.EvaluationUtils.getInitialSubmissionState;
import static org.sagebionetworks.EvaluationUtils.setStatus;
import static org.sagebionetworks.MessageUtils.LOGS_AVAILABLE_SUBJECT;
import static org.sagebionetworks.MessageUtils.SUBMISSION_PIPELINE_FAILURE_SUBJECT;
import static org.sagebionetworks.MessageUtils.WORKFLOW_COMPLETE_SUBJECT;
import static org.sagebionetworks.MessageUtils.WORKFLOW_FAILURE_SUBJECT;
import static org.sagebionetworks.MessageUtils.createLogsAvailableMessage;
import static org.sagebionetworks.MessageUtils.createPipelineFailureMessage;
import static org.sagebionetworks.MessageUtils.createWorkflowCompleteMessage;
import static org.sagebionetworks.MessageUtils.createWorkflowFailedMessage;
import static org.sagebionetworks.Utils.getProperty;
import static org.sagebionetworks.WorkflowUpdateStatus.DONE;
import static org.sagebionetworks.WorkflowUpdateStatus.ERROR_ENCOUNTERED_DURING_EXECUTION;
import static org.sagebionetworks.WorkflowUpdateStatus.IN_PROGRESS;
import static org.sagebionetworks.WorkflowUpdateStatus.STOPPED_TIME_OUT;
import static org.sagebionetworks.WorkflowUpdateStatus.STOPPED_UPON_REQUEST;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseConflictingUpdateException;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.repo.model.Annotations;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.auth.LoginRequest;
import org.sagebionetworks.repo.model.file.ExternalFileHandle;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class WorkflowHook  {
	private static Logger log = LoggerFactory.getLogger(WorkflowHook.class);

	// submission annotation names
	private static final String WORKFLOW_JOB_ID = "workflowJobId";
	private static final String LOGS_SUFFIX = "_logs";
	private static final long UPLOAD_PERIOD_MILLIS = 30*60*1000L; // 30 min in millis

	private SynapseClient synapse;
	private EvaluationUtils evaluationUtils;
	private SubmissionUtils submissionUtils;
	private MessageUtils messageUtils;
	private Archiver archiver;
	private ShutdownHook shutdownHook;
	private long sleepTimeMillis;
	private WES wes;

	public static void main( String[] args ) throws Throwable {
		SynapseClient synapse = SynapseClientFactory.createSynapseClient();
		String userName = getProperty(SYNAPSE_USERNAME_PROPERTY);
		String password = getProperty(SYNAPSE_PASSWORD_PROPERTY);
		LoginRequest loginRequest = new LoginRequest();
		loginRequest.setUsername(userName);
		loginRequest.setPassword(password);
		synapse.login(loginRequest);
		EvaluationUtils evaluationUtils = new EvaluationUtils(synapse);
		DockerUtils dockerUtils = new DockerUtils();
		SubmissionUtils submissionUtils = new SubmissionUtils(synapse);
		long sleepTimeMillis = 10*1000L;
		WorkflowHook agent = new WorkflowHook(
				synapse, evaluationUtils,
				dockerUtils, submissionUtils, sleepTimeMillis);
		agent.execute();
		log.info("At end of 'main'");
	}

	public WorkflowHook(SynapseClient synapse, 
			EvaluationUtils evaluationUtils, 
			DockerUtils dockerUtils, 
			SubmissionUtils submissionUtils,
			long sleepTimeMillis) throws SynapseException {
		this.sleepTimeMillis=sleepTimeMillis;
		this.shutdownHook = new ShutdownHook(Thread.currentThread());
		this.synapse=synapse;
		this.evaluationUtils=evaluationUtils;
		this.submissionUtils=submissionUtils;
		this.messageUtils=new MessageUtils(synapse);
		this.wes = new WES(dockerUtils);
		this.archiver = new Archiver(synapse, wes);

		// precheck
		dockerUtils.getInfo();
		log.info("Precheck completed successfully.");

	}
	
	public static List<String> getEvaluationIds() throws JSONException {
		return new ArrayList<String>(getTemplateSynapseIds().keySet());
	}

	public static Map<String,String> getTemplateSynapseIds() throws JSONException {
		String json = getProperty("EVALUATION_TEMPLATES");
		JSONObject templateMap = new JSONObject(json);
		Map<String,String> result = new HashMap<String,String>();
		for (Iterator<String> evaluationIdIterator=templateMap.keys(); evaluationIdIterator.hasNext();) {
			String evaluationId = evaluationIdIterator.next();
			String templateId = templateMap.getString(evaluationId);
			Utils.validateSynapseId(templateId);
			result.put(evaluationId, templateId);
		}
		return result;
	}

	public Map<String,WorkflowURLEntrypointAndSynapseRef> getWorkflowURLAndEntrypoint() throws Exception {
		Map<String,String> evaluationToSynIDMap = getTemplateSynapseIds();
		Map<String,WorkflowURLEntrypointAndSynapseRef> result = new HashMap<String,WorkflowURLEntrypointAndSynapseRef>();
		for (String evaluationId : evaluationToSynIDMap.keySet()) {
			String entityId = evaluationToSynIDMap.get(evaluationId);
			FileEntity fileEntity = synapse.getEntity(entityId, FileEntity.class);
			FileHandle fh = synapse.getRawFileHandle(fileEntity.getDataFileHandleId());
			if (fh instanceof ExternalFileHandle) {
				ExternalFileHandle efh = (ExternalFileHandle)fh;
				String urlString = efh.getExternalURL();
				URL url = new URL(urlString);
				// get annotation for the CWL entry point.  Does the file exist?
	   			Annotations annotations = synapse.getAnnotations(entityId);
	   			String rootTemplateString = annotations.getStringAnnotations().get(ROOT_TEMPLATE_ANNOTATION_NAME).get(0);
	   			result.put(evaluationId, new WorkflowURLEntrypointAndSynapseRef(url, rootTemplateString, entityId));
			} else {
				throw new IllegalArgumentException("Only ExternalFileHandle is supported.");
			}
		}
		return result;
	}
	
	private String myOwnPrincipalId = null;
	
	private String getNotificationPrincipalId() throws SynapseException {
		String id = getProperty(NOTIFICATION_PRINCIPAL_ID, false);
		if (!StringUtils.isEmpty(id)) return id;
		// if not set then just use my own ID
		if (myOwnPrincipalId!=null) return myOwnPrincipalId;
		myOwnPrincipalId=synapse.getMyProfile().getOwnerId();
		return myOwnPrincipalId;
	}

	public void execute() throws Throwable {
		Map<String,WorkflowURLEntrypointAndSynapseRef> evaluationIdToTemplateMap = getWorkflowURLAndEntrypoint();
		while (!shutdownHook.shouldShutDown()) { // this allows a system shut down to shut down the agent
			log.info("Top level loop: checking progress or starting new job.");

			for (String evaluationId : getEvaluationIds()) {
				WorkflowURLEntrypointAndSynapseRef workflow = evaluationIdToTemplateMap.get(evaluationId);
				createNewWorkflowJobs(evaluationId, workflow);
				updateWorkflowJobs(evaluationId);
			}

			try {
				Thread.sleep(sleepTimeMillis);
			} catch (InterruptedException e) {
				// continue
			}
		} // end while()
	} // end execute()

	public void createNewWorkflowJobs(String evaluationId, WorkflowURLEntrypointAndSynapseRef workflow) throws Throwable {
		List<SubmissionBundle> receivedSubmissions = 
				evaluationUtils.selectSubmissions(evaluationId, getInitialSubmissionState() );
		for (SubmissionBundle sb : receivedSubmissions) {
			String submissionId=sb.getSubmission().getId();
			SubmissionStatus submissionStatus = sb.getSubmissionStatus();
			try {
				if (BooleanUtils.isTrue(submissionStatus.getCancelRequested())) {
					SubmissionStatusModifications statusMods = new SubmissionStatusModifications();
					setStatus(statusMods, SubmissionStatusEnum.INVALID, WorkflowUpdateStatus.STOPPED_UPON_REQUEST);
					try {
						submissionUtils.updateSubmissionStatus(submissionStatus, statusMods);
					} catch (SynapseConflictingUpdateException e) {
						// do nothing
					}
					continue;
				}
				SubmissionStatusModifications statusMods = new SubmissionStatusModifications();
				initializeSubmissionAnnotations(statusMods);
				String workflowId = null;
				try {
					String submittingUserOrTeamId = SubmissionUtils.getSubmittingUserOrTeamId(sb.getSubmission());
					Folder sharedFolder=archiver.getOrCreateSubmitterFolder(submittingUserOrTeamId, true);
					Folder lockedFolder=archiver.getOrCreateSubmitterFolder(submittingUserOrTeamId, false);
					WorkflowParameters workflowParameters = new WorkflowParameters(
							sb.getSubmission().getId(), workflow.getSynapseId(), lockedFolder.getId(), sharedFolder.getId());
					byte[] synapseConfigFileContent;
					try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
						Utils.writeSynapseConfigFile(baos);
						synapseConfigFileContent = baos.toByteArray();
					}
					WorkflowJob newJob = wes.createWorkflowJob(workflow.getWorkflowUrl(), workflow.getEntryPoint(), workflowParameters, synapseConfigFileContent);
					workflowId = newJob.getWorkflowId();
					EvaluationUtils.setAnnotation(statusMods, WORKFLOW_JOB_ID, workflowId, PUBLIC_ANNOTATION_SETTING);
				} catch (InvalidSubmissionException e) {
					// mark as INVALID and notify SUBMITTER
					Submitter submitter = submissionUtils.getSubmitter(sb.getSubmission());
					String messageBody = createWorkflowFailedMessage(submitter.getName(), submissionId, e.getMessage(), null, null);
					submissionUtils.closeSubmissionAndSendNotification(submitter.getId(), submissionStatus, statusMods,
							SubmissionStatusEnum.INVALID, WorkflowUpdateStatus.ERROR_ENCOUNTERED_DURING_EXECUTION, e, WORKFLOW_FAILURE_SUBJECT, messageBody);
					continue;
				}

				try {
					submissionUtils.updateSubmissionStatus(submissionStatus, statusMods);
				} catch (Exception e) {
					throw new IllegalStateException("Started job "+workflowId+", but could not update submission "+submissionId, e);
				}
			} catch (final Throwable t) {
				log.error("Submission failed", t);
				String errorMessage = createPipelineFailureMessage(submissionId, null, ExceptionUtils.getStackTrace(t));
				// send this notification to an admin, not to the submitter
				messageUtils.sendMessage(getNotificationPrincipalId(), SUBMISSION_PIPELINE_FAILURE_SUBJECT, 
						errorMessage);
				throw t;
			}
		}
	}

	private static void initializeSubmissionAnnotations(SubmissionStatusModifications statusMods) {
		statusMods.setCancelRequested(false);
		statusMods.setCanCancel(true);
		EvaluationUtils.removeAnnotation(statusMods, WORKFLOW_JOB_ID);
		EvaluationUtils.removeAnnotation(statusMods, FAILURE_REASON);
		EvaluationUtils.removeAnnotation(statusMods, STATUS_DESCRIPTION);

		long now = System.currentTimeMillis();
		EvaluationUtils.setAnnotation(statusMods, JOB_STARTED_TIME_STAMP, now, ADMIN_ANNOTS_ARE_PRIVATE);
		EvaluationUtils.setAnnotation(statusMods, JOB_LAST_UPDATED_TIME_STAMP, now, PUBLIC_ANNOTATION_SETTING);
		statusMods.setStatus(getInProgressSubmissionState());    	
	}
	
	// returns map from workflow ID to Submission Bundle
	public static Map<String, SubmissionBundle> workflowIdsForSubmissions(List<SubmissionBundle> bundles) {
		Map<String, SubmissionBundle> result = new HashMap<String, SubmissionBundle>();
		for (SubmissionBundle b : bundles) {
			String workflowId = EvaluationUtils.getStringAnnotation(b.getSubmissionStatus(), WORKFLOW_JOB_ID);
			if (workflowId==null) throw new IllegalStateException("Submission "+b.getSubmission().getId()+" has no workflow job ID.");
			result.put(workflowId, b);
		}
		return result;
	}

	public static Map<String, WorkflowJob> workflowIdsForJobs(List<WorkflowJob> jobs) {
		Map<String, WorkflowJob> result = new HashMap<String, WorkflowJob>();
		for (WorkflowJob j : jobs) result.put(j.getWorkflowId(), j);
		return result;
	}

	public void updateWorkflowJobs(String evaluationId) throws Throwable {
		List<WorkflowJob> jobs=null;
		// list the running jobs according to Synapse
		List<SubmissionBundle> runningSubmissions = evaluationUtils.
				selectSubmissions(evaluationId, getInProgressSubmissionState());
		// list the running jobs according to the workflow system
		jobs = wes.listWorkflowJobs();
		// the two lists should be the same ...
		Map<String, WorkflowJob> workflowIdToJobMap = workflowIdsForJobs(jobs);
		Map<String, SubmissionBundle> workflowIdToSubmissionMap = workflowIdsForSubmissions(runningSubmissions);
		Set<WorkflowJob> jobsWithoutSubmissions = new HashSet<WorkflowJob>();
		for (String workflowId : workflowIdToJobMap.keySet()) {
			WorkflowJob jobForWorkflowId = workflowIdToJobMap.get(workflowId);
			SubmissionBundle submissionForWorkflowId = workflowIdToSubmissionMap.get(workflowId);
			if (submissionForWorkflowId==null) {
				jobsWithoutSubmissions.add(jobForWorkflowId);
			}
		}
		// if there  any running workflow jobs not in the EIP list, throw an IllegalStateException
		if (!jobsWithoutSubmissions.isEmpty()) {
			String msg = "Running workflow(s) without corresponding submission(s): "+jobsWithoutSubmissions;
			final String errorMessage = createPipelineFailureMessage(null, null, msg);
			messageUtils.sendMessage(getNotificationPrincipalId(), SUBMISSION_PIPELINE_FAILURE_SUBJECT, 
					errorMessage);
			throw new IllegalStateException(msg);
			// Note: An alternative is to kill the workflow(s) and let the WorkflowHook keep running.
		}

		Set<SubmissionBundle> submissionsWithoutJobs = new HashSet<SubmissionBundle>();
		for (String workflowId : workflowIdToSubmissionMap.keySet()) {
			if (workflowIdToJobMap.get(workflowId)==null) submissionsWithoutJobs.add(workflowIdToSubmissionMap.get(workflowId));
		}
		for (SubmissionBundle submissionBundle : submissionsWithoutJobs) {
			String messageBody = createPipelineFailureMessage(submissionBundle.getSubmission().getId(), null, "No running workflow found for submission.");
			submissionUtils.closeSubmissionAndSendNotification(getNotificationPrincipalId(), submissionBundle.getSubmissionStatus(), new SubmissionStatusModifications(),
					SubmissionStatusEnum.INVALID, WorkflowUpdateStatus.ERROR_ENCOUNTERED_DURING_EXECUTION, null, WORKFLOW_FAILURE_SUBJECT, messageBody);
		}

		// Now go through the list of running jobs, checking and updating each
		for (WorkflowJob job : jobs) {
			final SubmissionBundle submissionBundle = workflowIdToSubmissionMap.get(job.getWorkflowId());
			final Submission submission = submissionBundle.getSubmission();
			final SubmissionStatus submissionStatus = submissionBundle.getSubmissionStatus();
			final SubmissionStatusModifications statusMods = new SubmissionStatusModifications();
			
			try {
				Double progress = null;
				WorkflowUpdateStatus containerCompletionStatus = null;
				{
					WESWorkflowStatus initialWorkflowStatus = wes.getWorkflowStatus(job);
					progress = initialWorkflowStatus.getProgress();
					containerCompletionStatus = updateJob(job, initialWorkflowStatus, submissionBundle, statusMods);
				}
				switch(containerCompletionStatus) {
				case IN_PROGRESS:
					statusMods.setStatus(getInProgressSubmissionState());
					break;
				case DONE:
					statusMods.setStatus(getFinalSubmissionState());
					EvaluationUtils.removeAnnotation(statusMods, FAILURE_REASON);
					{
						Submitter submitter = submissionUtils.getSubmitter(submission);
						String messageBody = createWorkflowCompleteMessage(submitter.getName(), submission.getId(), 
								EvaluationUtils.getStringAnnotation(submissionStatus, SUBMISSION_ARTIFACTS_FOLDER));
						messageUtils.sendMessage(submitter.getId(), WORKFLOW_COMPLETE_SUBJECT,  messageBody);
					}
					break;
				case REJECTED:
					statusMods.setStatus(SubmissionStatusEnum.REJECTED);
					break;
				case ERROR_ENCOUNTERED_DURING_EXECUTION:
				case STOPPED_UPON_REQUEST:
				case STOPPED_TIME_OUT:
					statusMods.setStatus(SubmissionStatusEnum.INVALID);
					{
						Submitter submitter = submissionUtils.getSubmitter(submission);
						String messageBody = createWorkflowFailedMessage(submitter.getName(), submission.getId(), 
								EvaluationUtils.getStringAnnotation(submissionStatus, FAILURE_REASON), 
								null, 
								EvaluationUtils.getStringAnnotation(submissionStatus, SUBMISSION_ARTIFACTS_FOLDER));
						messageUtils.sendMessage(submitter.getId(), WORKFLOW_FAILURE_SUBJECT,  messageBody);
					}
					break;
				default:
					throw new IllegalStateException(containerCompletionStatus.toString());
				}
				EvaluationUtils.setAnnotation(statusMods, JOB_LAST_UPDATED_TIME_STAMP, System.currentTimeMillis(), PUBLIC_ANNOTATION_SETTING);
				if (progress!=null) {
					EvaluationUtils.setAnnotation(statusMods, PROGRESS, progress, false);
				}
				submissionUtils.updateSubmissionStatus(submissionStatus, statusMods);
			} catch (final Throwable t) {
				log.error("Pipeline failed", t);
				final String submissionId = job==null?null:submission.getId();
				final String workflowDescription = job==null?null:job.toString();
				final String errorMessage = createPipelineFailureMessage(submissionId, workflowDescription, ExceptionUtils.getStackTrace(t));
				// send this notification to an admin, not to the submitter
				messageUtils.sendMessage(getNotificationPrincipalId(), SUBMISSION_PIPELINE_FAILURE_SUBJECT, 
						errorMessage);

				throw t;
			}
		}
	}

	/*
	 * The possible states and the corresponding actions:
	 * 
	 * - container is running and there's no need to stop it or upload logs -> update the time stamp
	 * - container is running and there's no need to stop it but it's time to upload logs ->
	 * 		upload logs and (if haven't already) notify submitter where they can find their logs, update time stamp
	 * - container is running and either it's exceeded its time limit or user has requested stop ->
	 * 		stop the container, upload the final logs, update time stamp
	 * - container is stopped with exit code 0 ->
	 * 		upload the final logs, update time stamp
	 * - container is stopped with non-zero exit code ->
	 * 		upload the final logs, notify submitter that it failed, update time stamp
	 * 
	 * return the ContainerCompletionStatus
	 */
	public WorkflowUpdateStatus updateJob(final WorkflowJob job, WESWorkflowStatus workflowStatus, 
			SubmissionBundle submissionBundle, SubmissionStatusModifications statusMods) throws Throwable {
		final Submission submission = submissionBundle.getSubmission();
		final SubmissionStatus submissionStatus = submissionBundle.getSubmissionStatus();

		boolean isRunning = workflowStatus.isRunning();
		Integer exitCode = null; // null means that the container is running or interrupted (vs. stopped on its own)
		WorkflowUpdateStatus workflowUpdateStatus = null;

		SubmissionStatusEnum updatedStatus = null;
		String failureReason = null;
		Long updatedWhenLogFileSizeExceeded = null;
		Boolean updatedLogFileNotificationSent = null;
		Double progress = null;
		if (isRunning) {
			boolean userHasRequestedStop=submissionStatus.getCancelRequested()!=null && submissionStatus.getCancelRequested();
			boolean teamHasTimeRemaining = EvaluationUtils.hasTimeRemaining(EvaluationUtils.getTimeRemaining(submissionStatus));
			if (userHasRequestedStop || !teamHasTimeRemaining) {
				wes.stopWorkflowJob(job);
				if (userHasRequestedStop) {
					workflowUpdateStatus = STOPPED_UPON_REQUEST;
				} else if (!teamHasTimeRemaining) {
					workflowUpdateStatus = STOPPED_TIME_OUT;
				}
				updatedStatus = SubmissionStatusEnum.CLOSED;
			} else {
				progress = workflowStatus.getProgress();       
				log.info("PROGRESS: "+progress);
				workflowUpdateStatus = IN_PROGRESS;
			}
		} else {
			exitCode = workflowStatus.getExitCode();
			if (exitCode==0) {
				workflowUpdateStatus = DONE;
				updatedStatus = SubmissionStatusEnum.CLOSED;
			} else {
				if (exitCode==PROCESS_TERMINATED_ERROR_CODE) {
					workflowUpdateStatus = STOPPED_TIME_OUT;
					failureReason = STOPPED_TIME_OUT.toString();
				} else {
					workflowUpdateStatus = ERROR_ENCOUNTERED_DURING_EXECUTION;
					failureReason = ERROR_ENCOUNTERED_DURING_EXECUTION.toString();
				}
				updatedStatus = SubmissionStatusEnum.CLOSED;
			}
		}

		
		Long lastLogUploadTimeStamp = EvaluationUtils.getLongAnnotation(submissionStatus, LAST_LOG_UPLOAD);
		boolean timeToUploadLogs = lastLogUploadTimeStamp==null ||
				lastLogUploadTimeStamp+UPLOAD_PERIOD_MILLIS<System.currentTimeMillis();

		SubmissionFolderAndLogTail submissionFolderAndLogTail = null;
		String submissionFolderId = null;
		// we upload logs periodically and when container finally finishes, unless we've exceeded the maximum log size
		if ((true/*TODO not if log file size exceeded*/) && (timeToUploadLogs || !isRunning)) {
			String submittingUserOrTeamId = SubmissionUtils.getSubmittingUserOrTeamId(submission);
			Submitter submitter = submissionUtils.getSubmitter(submission);

			submissionFolderAndLogTail = archiver.uploadLogs(
					job, 
					submission.getId(),
					submittingUserOrTeamId, 
					LOGS_SUFFIX,
					MAX_LOG_ANNOTATION_CHARS);
			Folder submissionFolder = submissionFolderAndLogTail.getSubmissionFolder();
			submissionFolderId = submissionFolder==null?null:submissionFolder.getId();

			if (ERROR_ENCOUNTERED_DURING_EXECUTION.toString().equals(failureReason)) {
				failureReason = submissionFolderAndLogTail.getLogTail();
			}

			// NOTE:  We only send the initial notification for logs upload, not the 
			// final notification for the job having been completed.
			String haveLogsBeenSentString = EvaluationUtils.getStringAnnotation(submissionStatus, LOG_FILE_NOTIFICATION_SENT);
			boolean haveLogsBeenSent = haveLogsBeenSentString!=null && new Boolean(haveLogsBeenSentString);
			if (isRunning && submissionFolderId!=null && !haveLogsBeenSent) {
				String messageBody = createLogsAvailableMessage(submitter.getName(), submission.getId(), submissionFolderId);
				messageUtils.sendMessage(submittingUserOrTeamId, LOGS_AVAILABLE_SUBJECT, messageBody);
				updatedLogFileNotificationSent=true;
			}
		} // end uploading logs

		if (!isRunning) {
			wes.deleteWorkFlowJob(job);
		}

		EvaluationUtils.setAnnotation(statusMods, JOB_LAST_UPDATED_TIME_STAMP, System.currentTimeMillis(), PUBLIC_ANNOTATION_SETTING);
		if (submissionFolderId!=null) {
			EvaluationUtils.setAnnotation(statusMods, LAST_LOG_UPLOAD, System.currentTimeMillis(), ADMIN_ANNOTS_ARE_PRIVATE);    				
			EvaluationUtils.setAnnotation(statusMods, SUBMISSION_ARTIFACTS_FOLDER, submissionFolderId, PUBLIC_ANNOTATION_SETTING);
		}
		if (updatedWhenLogFileSizeExceeded!=null) {
			EvaluationUtils.setAnnotation(statusMods, LOG_FILE_SIZE_EXCEEDED, updatedWhenLogFileSizeExceeded.toString(), ADMIN_ANNOTS_ARE_PRIVATE);
		}
		if (updatedLogFileNotificationSent!=null) {
			EvaluationUtils.setAnnotation(statusMods, LOG_FILE_NOTIFICATION_SENT, updatedLogFileNotificationSent.toString(), ADMIN_ANNOTS_ARE_PRIVATE);
		}
		if (updatedStatus!=null) {
			EvaluationUtils.setStatus(statusMods, updatedStatus, workflowUpdateStatus);
		}
		if (failureReason!=null) {
			EvaluationUtils.setAnnotation(statusMods, FAILURE_REASON, failureReason, PUBLIC_ANNOTATION_SETTING);
		} else {
			EvaluationUtils.removeAnnotation(statusMods, FAILURE_REASON);
		}
		if (progress!=null) {
			EvaluationUtils.setAnnotation(statusMods, PROGRESS, progress, false);
		}

		if (workflowUpdateStatus==null) throw new IllegalStateException("Failed to set workflowUpdateStatus");
		return workflowUpdateStatus;
	}

}
