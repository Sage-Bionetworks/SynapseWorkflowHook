package org.sagebionetworks;

import static org.sagebionetworks.Constants.AGENT_TEMP_DIR_DEFAULT;
import static org.sagebionetworks.Constants.AGENT_TEMP_DIR_PROPERTY_NAME;
import static org.sagebionetworks.Constants.SYNAPSE_PASSWORD_PROPERTY;
import static org.sagebionetworks.Constants.SYNAPSE_USERNAME_PROPERTY;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.EntityBundle;
import org.sagebionetworks.repo.model.docker.DockerRepository;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.PreviewFileHandle;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;

import com.github.dockerjava.api.model.Container;

public class Utils {
	public static final String SYNAPSE_DOCKER_HOST = "docker.synapse.org";

	public static final long ONE_DAY_AS_MILLISEC = 24*3600*1000L;

	public static final String SEP = "."; // a string that's not contained in any token
	private static final String SEP_REGEXP = "\\.";
	private static final String WORKFLOW_CONTAINER_PREFIX = "workflow_job";
	private static final String ARCHIVE_PREFIX = "archive";

	public static final String DATE_FORMAT = "yyyy-MM-dd.HH:mm:ss";
	public static final String DECIMAL_PATTERN = "##.####";

	private static Properties properties = null;

	public static void initProperties() {
		if (properties!=null) return;
		properties = new Properties();
		InputStream is = null;
		try {
			is = Utils.class.getClassLoader().getResourceAsStream("global.properties");
			properties.load(is);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			if (is!=null) try {
				is.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	public static void validateSynapseId(String id) {
		if (!Pattern.matches(SubmissionUtils.SYNID_REGEX, id.toLowerCase().trim()))
			throw new RuntimeException(id+" is not a Synapse ID.");
		
	}

	public static String getSynIdProperty(String key) {
		String result = getProperty(key, true);
		validateSynapseId(result);
		return result;
	}

	public static String getProperty(String key) {
		return getProperty(key, true);
	}
	
	public static File createTempFile(String suffix, File parentFolder) throws IOException {
		return File.createTempFile("TMP", suffix, parentFolder);
	}
	
	public static File getTempDir() {
		return new File(System.getProperty("java.io.tmpdir"));
	}
	
	public static File getHostMountedScratchDir() {
		String agentTempDir = getProperty(AGENT_TEMP_DIR_PROPERTY_NAME, false);
		if (agentTempDir==null) agentTempDir = AGENT_TEMP_DIR_DEFAULT;
		return new File(agentTempDir);
	}
	private static boolean missing(String s) {
		return StringUtils.isEmpty(s) || "null".equals(s);
	}

	public static String getProperty(String key, boolean required) {
		initProperties();
		{
			String embeddedProperty = properties.getProperty(key);
			if (!missing(embeddedProperty)) return embeddedProperty;
		}
		{
			String environmentVariable = System.getenv(key);
			if (!missing(environmentVariable)) return environmentVariable;
		}
		{
			String commandlineOption = System.getProperty(key);
			if (!missing(commandlineOption)) return commandlineOption;
		}
		if (required) throw new RuntimeException("Cannot find value for "+key);
		return null;
	}
	
	public static void deleteFolderContent(File folder) {
		File[] files = folder.listFiles();
		if (files==null) return;
		for (File file : files) {
			if (file.isDirectory()) deleteFolderContent(file);
			file.delete();
		}
	}

	public static boolean isDirectoryEmpty(File dir) {
		File[] files = dir.listFiles();
		return files==null || files.length==0;
	}

	public static FileHandle getFileHandleFromEntityBundle(String s) {
		try {
			JSONObject bundle = new JSONObject(s);
			JSONArray fileHandles = (JSONArray)bundle.get("fileHandles");
			for (int i=0; i<fileHandles.length(); i++) {
				String jsonString = fileHandles.getString(i);
				FileHandle fileHandle = EntityFactory.createEntityFromJSONString(jsonString, FileHandle.class);
				if (!(fileHandle instanceof PreviewFileHandle)) return fileHandle;
			}
			throw new IllegalArgumentException("File has no file handle ID");
		} catch (JSONException e) {
			throw new RuntimeException(e);
		} catch (JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		}
	}

	public static String getDockerRepositoryNameFromEntityBundle(String s) {
		try {
			EntityBundle bundle = EntityFactory.createEntityFromJSONString(
					s, EntityBundle.class);
			return ((DockerRepository)bundle.getEntity()).getRepositoryName();
		} catch (JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		}
	}

	public static String createContainerName() {
		StringBuilder sb = new StringBuilder(WORKFLOW_CONTAINER_PREFIX);
		sb.append(SEP);
		sb.append(UUID.randomUUID()); 
		return sb.toString();
	}

	public static Filter WORKFLOW_FILTER = new Filter() {

		@Override
		public boolean match(String s) {
			return s.startsWith(WORKFLOW_CONTAINER_PREFIX);
		}

	};

	/*
	 * This is the name to give containers on the server once they stop running.
	 */
	public static String archiveContainerName(String name) {
		return ARCHIVE_PREFIX+SEP+name;
	}

	public static List<WorkflowJob> findRunningWorkflowJobs(Map<String, Container> agentContainers) {
		List<WorkflowJob> result = new ArrayList<WorkflowJob>();
		for (String containerName: agentContainers.keySet()) {
			WorkflowJobImpl job = new WorkflowJobImpl();
			job.setContainerName(containerName);
			job.setContainer(agentContainers.get(containerName));
			result.add(job);
		}
		return result;
	}

	public static Properties readPropertiesFile(InputStream is) {
		Properties properties = new Properties();
		try {
			try {
				properties.load(is);
			} finally {
				is.close();
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return properties;
	}

	public static long getTodayStart(long now) {
		return (now/ONE_DAY_AS_MILLISEC)*ONE_DAY_AS_MILLISEC;
	}

	public static String trunc(String s, int max) {
		return s.length()>max ? s.substring(0, max) : s;
	}

	/*
	 * return the numeric version of the first n characters of s,
	 * or null if s is empty or not numeric
	 */
	public static Double getProgressPercentFromString(String s, int n) {
		if (s==null) return null;
		s=s.replaceAll("STDOUT:", "");
		s=s.replaceAll("STDERR:", "");
		s=s.trim();
		if (s.length()>n) s = s.substring(0, n);
		try {
			double d = Double.parseDouble(s);
			if (d<0) return 0d;
			if (d>100) return 100d;
			return d;
		} catch (NumberFormatException e) {
			return null;			
		}
	}
	
	public static void writeSynapseConfigFile(OutputStream os) throws IOException {
		String username=getProperty(SYNAPSE_USERNAME_PROPERTY);
		String password=getProperty(SYNAPSE_PASSWORD_PROPERTY);;
		IOUtils.write("[authentication]\nusername="+username+"\npassword="+password+"\n", os, Charset.forName("UTF-8"));
	}

}
