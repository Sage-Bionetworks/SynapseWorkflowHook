package org.sagebionetworks;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.util.ValidateArgument;

import com.github.dockerjava.api.exception.NotFoundException;

public class FileUtils {
	DockerUtils dockerUtils;

	public static final String DIRECTORY_CLEANER_REPOSITORY= "docker.synapse.org/syn5644795/dm-directory-clean";
	public static final String DIRECTORY_TO_DELETE_MOUNT = "/directoryToDelete";
	public static final String CONTAINERNAME_SUFFIX = "directory-cleaner";
	private static final long DIRECTORY_CLEANER_WAIT_TIME = 10*1000;//10 seconds in miliseconds

	public FileUtils(DockerUtils dockerUtils) {
		this.dockerUtils = dockerUtils;
	}

	/**
	 * Initializes a new docker container that will delete all files in the specified directory in parallel.
	 * @param directories 
	 * @throws IOException
	 */
	public void clearFileDirectory(File hostDirectory, String submissionId) throws IOException{
		ValidateArgument.required(hostDirectory, "hostDirectory");
		ValidateArgument.required(submissionId, "submissionId");

		Map<File, String> directoryToDockerPathMap = new HashMap<File, String>();

		directoryToDockerPathMap.put(hostDirectory, DIRECTORY_TO_DELETE_MOUNT);

		//Note: the command here is just the files to delete since the ENTRYPOINT
		//in the docker image is already set as run.sh so we only need to provide 
		//arguments
		List<String> command = Collections.singletonList(DIRECTORY_TO_DELETE_MOUNT);

		Map<File, String> readOnlyVolumes = null;

		String containerId = dockerUtils.createModelContainer(DIRECTORY_CLEANER_REPOSITORY, 
				null,
				readOnlyVolumes, 
				directoryToDockerPathMap, 
				Collections.EMPTY_LIST,
				command,
				null);
		dockerUtils.startContainer(containerId);
		try {
			while (dockerUtils.getContainerState(containerId).getRunning()) {
				try {
					Thread.sleep(DIRECTORY_CLEANER_WAIT_TIME);
				} catch (InterruptedException e) {
					// continue
				}
			}
			int exitCode = dockerUtils.getContainerState(containerId).getExitCode();
			if(exitCode != 0){
				throw new IllegalStateException("Docker directory cleaner exited with code" + exitCode);
			}
		} finally {	
			try {
				dockerUtils.removeContainer(containerId, true);
			} catch (NotFoundException e) {
				// continue
			}
		}
	}
}
