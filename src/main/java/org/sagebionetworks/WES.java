package org.sagebionetworks;

import static org.sagebionetworks.Constants.AGENT_SHARED_DIR_PROPERTY_NAME;
import static org.sagebionetworks.Constants.DOCKER_CERT_PATH_HOST_PROPERTY_NAME;
import static org.sagebionetworks.Constants.DOCKER_ENGINE_URL_PROPERTY_NAME;
import static org.sagebionetworks.Constants.DUMP_PROGRESS_SHELL_COMMAND;
import static org.sagebionetworks.Constants.NUMBER_OF_PROGRESS_CHARACTERS;
import static org.sagebionetworks.Constants.SHARED_VOLUME_NAME;
import static org.sagebionetworks.Constants.TOIL_CLI_OPTIONS_PROPERTY_NAME;
import static org.sagebionetworks.Constants.UNIX_SOCKET_PREFIX;
import static org.sagebionetworks.Constants.WORKFLOW_ENGINE_DOCKER_IMAGES_PROPERTY_NAME;
import static org.sagebionetworks.Utils.WORKFLOW_FILTER;
import static org.sagebionetworks.Utils.archiveContainerName;
import static org.sagebionetworks.Utils.createTempFile;
import static org.sagebionetworks.Utils.findRunningWorkflowJobs;
import static org.sagebionetworks.Utils.getProperty;
import static org.sagebionetworks.Utils.getTempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.fuin.utils4j.Utils4J;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private static Logger log = LoggerFactory.getLogger(WES.class);

	private DockerUtils dockerUtils;
	
	private static final String ZIP_SUFFIX = ".zip";
	private static final String GA4GH_TRS_FILE_FRAGMENT = "/api/ga4gh/v2/tools";

	static {
		System.setProperty("https.protocols", "TLSv1,TLSv1.1,TLSv1.2"); // needed for some https resources
	}
		
	public WES(DockerUtils dockerUtils) {
		this.dockerUtils=dockerUtils;
	}
	
	private ContainerRelativeFile createDirInHostMountedSharedDir() {
		String name = UUID.randomUUID().toString();
		String mountPoint = dockerUtils.getVolumeMountPoint(SHARED_VOLUME_NAME);
		ContainerRelativeFile result = new ContainerRelativeFile(name, 
				new File(System.getProperty(AGENT_SHARED_DIR_PROPERTY_NAME)), new File(mountPoint));
		File dir = result.getContainerPath();
		if (!dir.mkdir()) throw new RuntimeException("Unable to create "+dir.getAbsolutePath());
		return result;
	}
	
	private static void downloadZip(final URL url, File tempDir, File target) throws IOException {
		File tempZipFile = createTempFile(".zip", tempDir);
		try {
			(new ExponentialBackoffRunner()).execute(new NoRefreshExecutableAdapter<Void,Void>() {
				@Override
				public Void execute(Void args) throws Throwable {
					try (InputStream is = url.openStream(); OutputStream os = new FileOutputStream(tempZipFile)) {
						IOUtils.copy(is, os);
					}
					return null;
				}}, null);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
		Utils4J.unzip(tempZipFile, target);
		tempZipFile.delete();
	}
	
	private static String downloadWebDocument(URL url) throws IOException {
		String result;
		try (InputStream is = url.openStream(); ByteArrayOutputStream os = new ByteArrayOutputStream()) {
			IOUtils.copy(is, os);
			result = os.toString();
		}
		return result;
	}
	
	private static final String PRIMARY_DESCRIPTOR_TYPE = "PRIMARY_DESCRIPTOR";
	private static final String SECONDARY_DESCRIPTOR_TYPE = "SECONDARY_DESCRIPTOR";
	
	
	public static void downloadWorkflowFromURL(URL workflowUrl, String entrypoint, File targetDir) throws IOException {
		String path = workflowUrl.getPath();
		if (path.toLowerCase().endsWith(ZIP_SUFFIX)) {
			downloadZip(workflowUrl, getTempDir(), targetDir);
   			// root file should be relative to unzip location
   			if (!(new File(targetDir,entrypoint)).exists()) {
   				throw new IllegalStateException(entrypoint+" is not in the unzipped archive downloaded from "+workflowUrl);
   			}
		} else if (path.contains(GA4GH_TRS_FILE_FRAGMENT)) {
			URL filesUrl = new URL(workflowUrl.toString()+"/files");
			String filesContent = downloadWebDocument(filesUrl);
			JSONArray files = new JSONArray(filesContent);
			for (int i=0; i<files.length(); i++) {
				JSONObject file = files.getJSONObject(i);
				String fileType = file.getString("file_type");
				String filePath = file.getString("path");
				if (PRIMARY_DESCRIPTOR_TYPE.equals(fileType)) {
					if (!filePath.equals(entrypoint)) throw new RuntimeException("Expected entryPoint "+entrypoint+" but found "+path);
				} else if (SECONDARY_DESCRIPTOR_TYPE.equals(fileType)) {
					// OK
				} else {
					throw new RuntimeException("Unexpected file_type "+fileType);
				}
				URL descriptorUrl = new URL(workflowUrl.toString()+"/descriptor/"+filePath);
				String descriptorContent = downloadWebDocument(descriptorUrl);
				JSONObject descriptor = new JSONObject(descriptorContent);
				try (OutputStream os = new FileOutputStream(new File(targetDir, filePath))) {
					IOUtils.write(descriptor.getString("content"), os, Charset.forName("utf-8"));
				}
			}
			
		} else {
			throw new RuntimeException("Expected template to be a zip archive or TRS files URL, but found "+path);
		}
	}
	
	private ContainerRelativeFile createWorkflowParametersYamlFile(WorkflowParameters params, ContainerRelativeFile targetFolder,
			File hostSynapseConfig) throws IOException {
		File workflowParameters = createTempFile(".yaml", targetFolder.getContainerPath());
		try (FileOutputStream fos = new FileOutputStream(workflowParameters)) {
			IOUtils.write("submissionId: "+params.getSubmissionId()+"\n", fos, Charset.forName("UTF-8"));
			IOUtils.write("workflowSynapseId: "+params.getSynapseWorkflowReference()+"\n", fos, Charset.forName("UTF-8"));
			IOUtils.write("submitterUploadSynId: "+params.getSubmitterUploadSynId()+"\n", fos, Charset.forName("UTF-8"));
			IOUtils.write("adminUploadSynId: "+params.getAdminUploadSynId()+"\n", fos, Charset.forName("UTF-8"));
			IOUtils.write("synapseConfig:\n  class: File\n  path: "+hostSynapseConfig.getAbsolutePath()+"\n", fos, Charset.forName("UTF-8"));
		}
		return new ContainerRelativeFile(workflowParameters.getName(), targetFolder.getContainerPath(), targetFolder.getHostPath());
	}

	private static final String[] DISALLOWED_USER_TOIL_PARAMS = {
			"LinkImports", // we apply noLinkImports.  User must use neither LinkImports nor noLinkImports
			"workDir" // we specify workDir.  User must not
	};
	
	/**
	 * This is analogous to POST /workflows in WES
	 * @param workflowUrl the URL to the archive of workflow files
	 * @param entrypoint the entry point (a file path) within an unzipped workflow archive
	 * @param workflowParameters the parameters to be passed to the workflow
	 * @return the created workflow job
	 * @throws IOException
	 */
	public WorkflowJob createWorkflowJob(URL workflowUrl, String entrypoint, 
			WorkflowParameters workflowParameters, byte[] synapseConfigFileContent) throws IOException {
		ContainerRelativeFile workflowFolder = createDirInHostMountedSharedDir();

		downloadWorkflowFromURL(workflowUrl, entrypoint, workflowFolder.getContainerPath());
		
		// The folder with the workflow and param's, from the POV of the host
		File hostWorkflowFolder = workflowFolder.getHostPath();
		// To run Toil inside Docker we need to make certain settings as explained here:
		// https://github.com/brucehoff/wiki/wiki/Problem-running-Toil-in-a-container
		// For one thing, the path to the folder from the workflow's POV must be the SAME as from the host's POV.
		File workflowRunnerWorkflowFolder = hostWorkflowFolder;
		
		// write the synapse config file into the workflow folder
		// this is NOT secure but there's no good option today
		try (FileOutputStream fos=new FileOutputStream(new File(workflowFolder.getContainerPath(), ".synapseConfig"))) {
			IOUtils.write(synapseConfigFileContent, fos);
		}
		
		// Note that we create the param's file within the folder to which we've downloaded the workflow template
		// This gives us a single folder to mount to the Toil container
		ContainerRelativeFile workflowParametersFile = createWorkflowParametersYamlFile(workflowParameters, workflowFolder, 
				new File(workflowFolder.getHostPath(), ".synapseConfig"));
		
		List<String> cmd = new ArrayList<String>();
		cmd.add("toil-cwl-runner");

		String userToilParams = getProperty(TOIL_CLI_OPTIONS_PROPERTY_NAME, false);
		if (StringUtils.isEmpty(userToilParams)) userToilParams="";
		for (String disallowed : DISALLOWED_USER_TOIL_PARAMS) {
			if (userToilParams.toLowerCase().contains(disallowed.toLowerCase())) {
				throw new IllegalArgumentException("may not specify "+disallowed+" in Toil CLI options.");
			}
		}
		for (String toilParam : userToilParams.split("\\s+")) {
			if (!StringUtils.isEmpty(toilParam)) cmd.add(toilParam);
		}
		
		// further, we must set 'workDir' and 'noLinkImports':
		cmd.addAll(Arrays.asList("--workDir",  
				workflowRunnerWorkflowFolder.getAbsolutePath(), 
				"--noLinkImports",
				entrypoint,
				workflowParametersFile.getHostPath().getAbsolutePath()
				));

		Map<File,String> rwVolumes = new HashMap<File,String>();
		
		String containerName = Utils.createContainerName();
		String containerId = null;
		Map<File,String> roVolumes = new HashMap<File,String>();
		
		List<String> containerEnv = new ArrayList<String>();
		
		// workDir doesn't seem to work.  Let's try TMPDIR, TEMP, TMP, as per
		// https://toil.readthedocs.io/en/latest/running/cliOptions.html
		containerEnv.add("TMPDIR="+workflowRunnerWorkflowFolder.getAbsolutePath());
		containerEnv.add("TEMP="+workflowRunnerWorkflowFolder.getAbsolutePath());
		containerEnv.add("TMP="+workflowRunnerWorkflowFolder.getAbsolutePath());
		
		
		// pass Docker daemon URL and cert's folder, if any, so the container we launch can run Docker too
		// the volume mount and env setting to let the Docker client access the daemon are defined here:
		// https://docs.docker.com/engine/security/https/
		String dockerHost = getProperty(DOCKER_ENGINE_URL_PROPERTY_NAME);
		containerEnv.add("DOCKER_HOST="+dockerHost);
		// mount certs folder, if any
		String hostCertsFolder = getProperty(DOCKER_CERT_PATH_HOST_PROPERTY_NAME, false);
		if (!StringUtils.isEmpty(hostCertsFolder)) {
			roVolumes.put(new File(hostCertsFolder), "/root/.docker/");
		}
		
		if (dockerHost.startsWith(UNIX_SOCKET_PREFIX)) {
			String volumeToMount = dockerHost.substring(UNIX_SOCKET_PREFIX.length());
			log.info("Mounting: "+volumeToMount);
			rwVolumes.put(new File(volumeToMount), volumeToMount);
		}

		rwVolumes.put(hostWorkflowFolder, workflowRunnerWorkflowFolder.getAbsolutePath());
		String workingDir = workflowRunnerWorkflowFolder.getAbsolutePath();
		
		log.info("workingDir: "+workingDir);
		log.info("toil cmd: "+cmd);
		
		String workflowEngineImage = getProperty(WORKFLOW_ENGINE_DOCKER_IMAGES_PROPERTY_NAME, false);
		// normally would use quay.io ("quay.io/ucsc_cgl/toil")
		// but sagebionetworks/synapseworkflowhook-toil incorporates the Synapse client as well at Toil and Docker
		if (StringUtils.isEmpty(workflowEngineImage)) workflowEngineImage = "sagebionetworks/synapseworkflowhook-toil";
		
		try {
			containerId = dockerUtils.createModelContainer(
					workflowEngineImage,
					containerName, 
					roVolumes,
					rwVolumes, 
					containerEnv,
					cmd,
					workingDir
					);

			dockerUtils.startContainer(containerId);
		} catch (DockerPullException e) {
			if (containerId!=null) dockerUtils.removeContainer(containerId, true);

			throw e;
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
		}
	}

}
