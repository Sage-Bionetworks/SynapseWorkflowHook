package org.sagebionetworks;

import java.util.Arrays;
import java.util.List;

import org.sagebionetworks.client.exceptions.SynapseBadRequestException;
import org.sagebionetworks.client.exceptions.SynapseConflictingUpdateException;
import org.sagebionetworks.client.exceptions.SynapseDeprecatedServiceException;
import org.sagebionetworks.client.exceptions.SynapseForbiddenException;
import org.sagebionetworks.client.exceptions.SynapseLockedException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.client.exceptions.SynapseResultNotReadyException;
import org.sagebionetworks.client.exceptions.SynapseServerException;
import org.sagebionetworks.client.exceptions.SynapseTermsOfUseException;
import org.sagebionetworks.client.exceptions.SynapseUnauthorizedException;

public class Constants {
	
	public static final String SYNAPSE_USERNAME_PROPERTY = "SYNAPSE_USERNAME";
	
	public static final String SYNAPSE_PASSWORD_PROPERTY = "SYNAPSE_PASSWORD";
	
	// This the Synapse ID of the user or team to whom administrative notifications are sent
	public static final String NOTIFICATION_PRINCIPAL_ID = "NOTIFICATION_PRINCIPAL_ID";

	// property names appear as "-e" options in the "docker run" command that launches this agent
	// these folders are the paths on the host
	
	// This is the name of a directory mounted from the host so that files created by the agent can
	// be shared externally (in particular with the workflow)
	public static final String HOST_TEMP_DIR_PROPERTY_NAME = "HOST_TEMP";
	
	public static final String TOIL_CLI_OPTIONS_PROPERTY_NAME = "TOIL_CLI_OPTIONS";

	// these are the paths as they appear in the agent container, 
	// i.e. the agent container is run with "-v /host/path:/agent/path
	// the agent may not need to access all the folders but mounting them allows it to verify that
	// they are present on the host
	public static final String AGENT_TEMP_DIR_PROPERTY_NAME = "AGENT_TEMP_DIR";
	public static final String AGENT_TEMP_DIR_DEFAULT = "/tempDir";

	// folder names as they appear to the workflow
	// these are used in the "docker run" command that this agent uses to
	// launch the container which runs the workflow
	public static final String WORKFLOW_TEMP_DIR = "/tempDir";
	
	public static final String WORKFLOW_SYNAPSE_CONFIG_FOLDER = "/root";

	public static final String WORKFLOW_SYNAPSE_CONFIG_FILE_NAME = ".synapseConfig";

	public static final String ROOT_TEMPLATE_ANNOTATION_NAME="ROOT_TEMPLATE";

	/*
	 * If true then rather than removing containers when they finish running, just
	 * archive them for later reference.
	 */
	public static final boolean ARCHIVE_CONTAINER = false;

	public static final int MAX_LOG_ANNOTATION_CHARS = 256; // maximum log tail length to insert in annotation

	public static final long MEGABYTE_IN_BYTES = 1<<20;
	public static final long GIGABYTE_IN_BYTES = 1<<30;

	public static final String[] DUMP_PROGRESS_SHELL_COMMAND = {"cat","/progress.txt"};
	public static final int NUMBER_OF_PROGRESS_CHARACTERS = 5;
	
	public static final String EXECUTION_STAGE_PROPERTY_NAME = "EXECUTION_STAGE";
	
	public static final List<Class<? extends SynapseServerException>> NO_RETRY_EXCEPTIONS = Arrays.asList(
			SynapseResultNotReadyException.class,
			SynapseNotFoundException.class,
			SynapseBadRequestException.class,
			SynapseConflictingUpdateException.class,
			SynapseDeprecatedServiceException.class,
			SynapseForbiddenException.class, 
			SynapseTermsOfUseException.class,
			SynapseUnauthorizedException.class
			); 
	
	public static final Integer[] NO_RETRY_STATUSES = new Integer[] {409};
	
	public static int DEFAULT_NUM_RETRY_ATTEMPTS = 8; // 63 sec

}
