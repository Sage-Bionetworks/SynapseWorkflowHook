package org.sagebionetworks;

import static org.sagebionetworks.Constants.DUMP_PROGRESS_SHELL_COMMAND;
import static org.sagebionetworks.Constants.HOST_TEMP_DIR_PROPERTY_NAME;
import static org.sagebionetworks.Constants.NUMBER_OF_PROGRESS_CHARACTERS;
import static org.sagebionetworks.Constants.WORKFLOW_TEMP_DIR;
import static org.sagebionetworks.Utils.WORKFLOW_FILTER;
import static org.sagebionetworks.Utils.archiveContainerName;
import static org.sagebionetworks.Utils.findRunningWorkflowJobs;
import static org.sagebionetworks.Utils.getProperty;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.model.Container;

/**
 * This class is a layer of abstraction to represent what would be done
 * through the Workflow Excecution Schema (create, list, delete workflow jobs)
 * See http://ga4gh.github.io/workflow-execution-service-schemas
 * 
 * @author bhoff
 *
 */
public class WES {
	private DockerUtils dockerUtils;
	
	public WES(DockerUtils dockerUtils) {
		this.dockerUtils=dockerUtils;
	}

	/**
	 * This is analogous to POST /workflows in WES
	 * @param templateFolderAndRootTemplate the file path to the workflow and root template, relative to the temp folder
	 * @param workflowParameters the file path to the workflow parameters, relative to the temp folder
	 * @return the created workflow job
	 */
	public WorkflowJob createWorkflowJob(FolderAndFile templateFolderAndRootTemplate, ContainerRelativeFile workflowParameters, Map<File,String> additionalROVolumeMounts) throws IOException, InvalidSubmissionException {
		ContainerRelativeFile templateFolder = templateFolderAndRootTemplate.getFolder(); // relative to 'temp' folder which is mounted to the container
		File rootTemplate = templateFolderAndRootTemplate.getFile(); // relative to templateFolder
		// the two paths, from the point of view of the host running this process
		File hostTemplateFolder = templateFolder.getFullPath(new File(getProperty(HOST_TEMP_DIR_PROPERTY_NAME)));
		File hostWorkflowParameters = workflowParameters.getFullPath(new File(getProperty(HOST_TEMP_DIR_PROPERTY_NAME)));
		// the two paths, from the point of view of the workflow engine
		File workflowTemplateFolder = templateFolder.getFullPath(new File(WORKFLOW_TEMP_DIR));
		File workflowWorkflowParameters = workflowParameters.getFullPath(new File(WORKFLOW_TEMP_DIR));

		List<String> cmd = Arrays.asList(
				"toil-cwl-runner", 
				"--defaultMemory",  "100M", 
				"--retryCount",  "0", 
				"--defaultDisk", "1000000",
				rootTemplate.getPath(),
				workflowWorkflowParameters.getAbsolutePath()
				);

		Map<File,String> rwVolumes = new HashMap<File,String>();
		
		String containerName = Utils.createContainerName();
		String containerId = null;
		Map<File,String> roVolumes = new HashMap<File,String>(additionalROVolumeMounts);
		rwVolumes.put(hostTemplateFolder, workflowTemplateFolder.getAbsolutePath());
		roVolumes.put(hostWorkflowParameters, workflowWorkflowParameters.getAbsolutePath());
		String workingDir = workflowTemplateFolder.getAbsolutePath();
		try {
			// normally would pull from quqy.io ("quay.io/ucsc_cgl/toil")
			// this incorporates the Synapse client as well at Toil and Docker
			containerId = dockerUtils.createModelContainer(
					"docker.synapse.org/syn5644795/docker-and-toil",
					containerName, 
					roVolumes, 
					rwVolumes, 
					Collections.EMPTY_LIST, 
					cmd,
					workingDir
					);

			dockerUtils.startContainer(containerId);
		} catch (DockerPullException e) {
			if (containerId!=null) dockerUtils.removeContainer(containerId, true);

			throw new InvalidSubmissionException(e);
		}

		WorkflowJobImpl workflowJob = new WorkflowJobImpl();
		workflowJob.setContainerName(containerName);
		return workflowJob;
	}

	/*
	 * This is analogous to GET /workflows in WES
	 */
	public List<WorkflowJob> listWorkflowJobs() {
		return findRunningWorkflowJobs(dockerUtils.listContainers(WORKFLOW_FILTER));
	}
	
	/*
	 * This is analogous to GET /workflows/{workflow_id}/status in WES
	 */
	public WESWorkflowStatus getWorkflowStatus(WorkflowJob job) throws IOException {
		WorkflowJobImpl j = (WorkflowJobImpl)job;
		Container container = j.getContainer();
		WESWorkflowStatus result = new WESWorkflowStatus();
		ContainerState containerState = dockerUtils.getContainerState(container.getId());
		result.setRunning(containerState.getRunning());
		result.setExitCode(containerState.getExitCode());
		if (containerState.getRunning()) {
			String execOutput = dockerUtils.exec(container.getId(), DUMP_PROGRESS_SHELL_COMMAND);
			result.setProgress(Utils.getProgressPercentFromString(execOutput, NUMBER_OF_PROGRESS_CHARACTERS));        		
		}
		return result;
	}
	
	/*
	 * This would be provided by GET /workflows/{workflow_id} WES
	 * 
	 * Writes full log to a file and optionally returns just the tail (if 'maxTailLengthInCharacters' is not null)
	 */
	public String getWorkflowLog(WorkflowJob job, Path outPath, Integer maxTailLengthInCharacters) throws IOException {
		WorkflowJobImpl j = (WorkflowJobImpl)job;
		return dockerUtils.getLogs(j.getContainer().getId(), outPath, maxTailLengthInCharacters);
	}
	
	/*
	 * This has no analogy in WES.  The idea is to have a state for a workflow in which it is
	 * interrupted but not deleted.  In this state the logs can be interrogated to show just
	 * where the interruption occurred.
	 */
	public void stopWorkflowJob(WorkflowJob job) {
		WorkflowJobImpl j = (WorkflowJobImpl)job;
		ContainerState containerState = dockerUtils.getContainerState(j.getContainer().getId());
		if (containerState.getRunning()) {
			dockerUtils.stopContainerWithRetry(j.getContainer().getId());			
		}		
	}

	/*
	 * This is analogous to DELETE /workflows/{workflow_id} in WES
	 */
	public void deleteWorkFlowJob(WorkflowJob job) {
		// stop it if it's running
		stopWorkflowJob(job);
		
		// now delete or archive the job
		WorkflowJobImpl j = (WorkflowJobImpl)job;
		if (Constants.ARCHIVE_CONTAINER) {
			dockerUtils.renameContainer(j.getContainer().getId(), archiveContainerName(j.getContainerName()));
		} else {
			dockerUtils.removeContainer(j.getContainer().getId(), true);
			dockerUtils.removeImage(j.getContainer().getImageId(), true);
		}
	}

}
