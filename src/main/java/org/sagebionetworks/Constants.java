package org.sagebionetworks;


public class Constants {
	
	public static final String SYNAPSE_USERNAME_PROPERTY = "SYNAPSE_USERNAME";
	
	public static final String SYNAPSE_PASSWORD_PROPERTY = "SYNAPSE_PASSWORD";
	
	// This the Synapse ID of the user or team to whom administrative notifications are sent
	public static final String NOTIFICATION_PRINCIPAL_ID = "NOTIFICATION_PRINCIPAL_ID";

	// property names appear as "-e" options in the "docker run" command that launches this agent
	// these folders are the paths on the host
	public static final String HOST_TEMP_DIR_PROPERTY_NAME = "HOST_TEMP";

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
	
	public static final String WORKFLOW_SYNPASE_CONFIG = "/root/.synapseConfig";

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
}