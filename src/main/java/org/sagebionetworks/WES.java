package org.sagebionetworks;

import static org.sagebionetworks.Constants.DUMP_PROGRESS_SHELL_COMMAND;
import static org.sagebionetworks.Constants.HOST_TEMP_DIR_PROPERTY_NAME;
import static org.sagebionetworks.Constants.NUMBER_OF_PROGRESS_CHARACTERS;
import static org.sagebionetworks.Utils.WORKFLOW_FILTER;
import static org.sagebionetworks.Utils.archiveContainerName;
import static org.sagebionetworks.Utils.createTempFile;
import static org.sagebionetworks.Utils.findRunningWorkflowJobs;
import static org.sagebionetworks.Utils.getHostMountedScratchDir;
import static org.sagebionetworks.Utils.getProperty;
import static org.sagebionetworks.Utils.getTempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.fuin.utils4j.Utils4J;

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
	
	private static ContainerRelativeFile createDirInHostMountedScratchDir() {
		ContainerRelativeFile result = new ContainerRelativeFile(UUID.randomUUID().toString(), getHostMountedScratchDir(), new File(getProperty(HOST_TEMP_DIR_PROPERTY_NAME)));
		File dir = result.getContainerPath();
		if (!dir.mkdir()) throw new RuntimeException("Unable to create "+dir.getAbsolutePath());
		return result;
	}
	
	private static void downloadZip(URL url, File tempDir, File target) throws IOException {
		System.setProperty("https.protocols", "TLSv1,TLSv1.1,TLSv1.2"); // needed for some https resources
		File tempZipFile = createTempFile(".zip", tempDir);
		try (InputStream is = url.openStream(); OutputStream os = new FileOutputStream(tempZipFile)) {
			IOUtils.copy(is, os);
		}
		Utils4J.unzip(tempZipFile, target);
		tempZipFile.delete();
	}

	private ContainerRelativeFile downloadWorkflowFromURL(URL workflowUrl, String entrypoint) throws IOException {
		ContainerRelativeFile workflowTemplateFolder = createDirInHostMountedScratchDir();
		String path = workflowUrl.getPath();
		if (path.toLowerCase().endsWith("zip")) {
			downloadZip(workflowUrl, getTempDir(), workflowTemplateFolder.getContainerPath());
   			// root file should be relative to unzip location
   			if (!(new File(workflowTemplateFolder.getContainerPath(),entrypoint)).exists()) {
   				throw new IllegalStateException(entrypoint+" is not in the unzipped archive downloaded from "+workflowUrl);
   			}
		} else {
			throw new RuntimeException("Expected template to be a zip archive, bound found "+path);
		}
		return workflowTemplateFolder;
	}
	
	private ContainerRelativeFile createWorkflowParametersYamlFile(WorkflowParameters params, ContainerRelativeFile targetFolder) throws IOException {
		File workflowParameters = createTempFile(".yml", targetFolder.getContainerPath());
		try (FileOutputStream fos = new FileOutputStream(workflowParameters)) {
			IOUtils.write("submissionId: "+params.getSubmissionId()+"\n", fos, Charset.forName("UTF-8"));
			IOUtils.write("workflowSynapseId: "+params.getSynapseWorkflowReference()+"\n", fos, Charset.forName("UTF-8"));
			IOUtils.write("submitterUploadSynId: "+params.getSubmitterUploadSynId()+"\n", fos, Charset.forName("UTF-8"));
			IOUtils.write("adminUploadSynId: "+params.getAdminUploadSynId()+"\n", fos, Charset.forName("UTF-8"));
		}
		return new ContainerRelativeFile(workflowParameters.getName(), targetFolder.getContainerPath(), targetFolder.getHostPath());
	}
	
	
	private Map<File,String> additionalROVolumeMounts = new HashMap<File,String>();
	
	/*
	 * Used for configuration files required by the workflow engine
	 */
	public void addWorkflowEngineFile(ContainerRelativeFile file, File workflowEngineFolder, String relativePath) {
		additionalROVolumeMounts.put(file.getHostPath(), (new File(workflowEngineFolder, relativePath)).getAbsolutePath());
	}

	/**
	 * This is analogous to POST /workflows in WES
	 * @param workflowUrl the URL to the archive of workflow files
	 * @param entrypoint the entry point (a file path) within the unzipped workflow archive
	 * @param workflowParameters the parameters to be passed to the workflow
	 * @return the created workflow job
	 * @throws IOException
	 * @throws InvalidSubmissionException
	 */
	public WorkflowJob createWorkflowJob(URL workflowUrl, String entrypoint, WorkflowParameters workflowParameters) throws IOException, InvalidSubmissionException {
		ContainerRelativeFile templateFolder = downloadWorkflowFromURL(workflowUrl, entrypoint);// relative to 'temp' folder which is mounted to the container
		// Note that we create the param's file within the folder to which we've downloaded the workflow template
		// This gives us a single folder to mount to the Toil container
		ContainerRelativeFile workflowParametersFile = createWorkflowParametersYamlFile(workflowParameters, templateFolder);
		
		// The folder with the workflow and param's, from the POV of the host
		File hostTemplateFolder = templateFolder.getHostPath();
		// To run Toil inside Docker we need to make certain settings as explained here:
		// https://github.com/brucehoff/wiki/wiki/Problem-running-Toil-in-a-container
		// For one thing, the path to the folder from the workflow's POV must be the SAME as from the host's POV.
		File workflowTemplateFolder = hostTemplateFolder;
		
		// further, we must set 'workDir' and 'noLinkImports':
		List<String> cmd = Arrays.asList(
				"toil-cwl-runner", 
				"--defaultMemory",  "100M", 
				"--retryCount",  "0", 
				"--defaultDisk", "1000000",
				"--workDir",  workflowTemplateFolder.getAbsolutePath(), 
				"--noLinkImports",
				entrypoint,
				workflowParametersFile.getHostPath().getAbsolutePath()
				);

		Map<File,String> rwVolumes = new HashMap<File,String>();
		
		String containerName = Utils.createContainerName();
		String containerId = null;
		Map<File,String> roVolumes = new HashMap<File,String>(additionalROVolumeMounts);
		rwVolumes.put(hostTemplateFolder, workflowTemplateFolder.getAbsolutePath());
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
