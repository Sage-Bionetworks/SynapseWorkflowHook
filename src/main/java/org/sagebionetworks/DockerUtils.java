package org.sagebionetworks;

import static org.sagebionetworks.Constants.DOCKER_CERT_PATH_PROPERTY_NAME;
import static org.sagebionetworks.Constants.DOCKER_ENGINE_URL_PROPERTY_NAME;
import static org.sagebionetworks.Constants.SYNAPSE_PASSWORD_PROPERTY;
import static org.sagebionetworks.Constants.SYNAPSE_USERNAME_PROPERTY;
import static org.sagebionetworks.Constants.UNIX_SOCKET_PREFIX;
import static org.sagebionetworks.Utils.getProperty;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.glassfish.jersey.internal.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.util.DockerNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Device;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.LogConfig.LoggingType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig.Builder;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.github.dockerjava.core.command.PushImageResultCallback;

public class DockerUtils {
	private static final int MAX_RETRIES = 10;
	private static final int MAX_CONTAINER_STOP_RETRIES = 15;

	private static final String DOCKERHUB_REGISTRY_ADDRESS = "index.docker.io";
	private static final String DOCKERHUB_EMAIL = "not-used-but-cannot-be-null";
	private static final String SYNAPSE_REGISTRY_ADDRESS = "docker.synapse.org";
	private static final String SYNAPSE_EMAIL = "not-used-but-cannot-be-null";
	
	private static final String DOCKERHUB_USERNAME_PROPERTY = "DOCKERHUB_USERNAME";
	private static final String DOCKERHUB_PASSWORD_PROPERTY = "DOCKERHUB_PASSWORD";

	public static final int PROCESS_TERMINATED_ERROR_CODE = 137;

	private DockerClient dockerClient;
	// to interact with DockerHub must be logged in with DockerHub credentials
	private DockerClient dockerHubClient;

	public DockerClient getDockerClient() {
		return dockerClient;
	}

	Logger log = LoggerFactory.getLogger(DockerUtils.class);

	private static void validateCertPath(String certPath) {
		File keyFile = new File(certPath, "key.pem");
		if (!keyFile.exists())
			throw new IllegalStateException(keyFile.getAbsolutePath()
					+ " does not exist.");
		File certFile = new File(certPath, "cert.pem");
		if (!certFile.exists())
			throw new IllegalStateException(certFile.getAbsolutePath()
					+ " does not exist.");
	}
	
	public DockerUtils() {
		// from https://github.com/docker-java/docker-java/wiki
		String dockerEngineURL = getProperty(DOCKER_ENGINE_URL_PROPERTY_NAME);
		
		String synapseUsername = getProperty(SYNAPSE_USERNAME_PROPERTY, /* required */
				false);
		String synapsePassword = getProperty(SYNAPSE_PASSWORD_PROPERTY, /* required */
				false);
		// https://groups.google.com/forum/?#!searchin/docker-java-dev/https$20protocol$20is$20not$20supported/docker-java-dev/6B13qxZ4eBM/UkyOCsYWBwAJ
		Builder synapseConfigBuilder = DefaultDockerClientConfig
				.createDefaultConfigBuilder().withDockerHost(dockerEngineURL)
				.withRegistryUsername(synapseUsername)
				.withRegistryPassword(synapsePassword)
				.withRegistryEmail(SYNAPSE_EMAIL)
				.withRegistryUrl(SYNAPSE_REGISTRY_ADDRESS);
		
		String dockerHubUsername = getProperty(DOCKERHUB_USERNAME_PROPERTY, /* required */
				false);
		String dockerHubPassword = getProperty(DOCKERHUB_PASSWORD_PROPERTY, /* required */
				false);
		Builder dockerhubConfigBuilder = DefaultDockerClientConfig
				.createDefaultConfigBuilder().withDockerHost(dockerEngineURL)
				.withRegistryUsername(dockerHubUsername)
				.withRegistryPassword(dockerHubPassword)
				.withRegistryEmail(DOCKERHUB_EMAIL)
				.withRegistryUrl(DOCKERHUB_REGISTRY_ADDRESS);

		if (!dockerEngineURL.toLowerCase().startsWith(UNIX_SOCKET_PREFIX)) {
			String certificatePath = getProperty(DOCKER_CERT_PATH_PROPERTY_NAME);
			validateCertPath(certificatePath);
			synapseConfigBuilder=synapseConfigBuilder
					.withDockerCertPath(certificatePath)
					.withDockerTlsVerify(true);
			dockerhubConfigBuilder=dockerhubConfigBuilder
					.withDockerCertPath(certificatePath)
					.withDockerTlsVerify(true);
		}

		dockerClient = DockerClientBuilder.getInstance(
				synapseConfigBuilder.build()).build();

		dockerHubClient = DockerClientBuilder.getInstance(
				dockerhubConfigBuilder.build()).build();
	}

	public Info getInfo() {
		return dockerClient.infoCmd().exec();
	}

	public static void addVolumes(Map<File, String> volumes, String volumesList) {
		if (volumesList != null) {
			String[] volumeStrings = volumesList.split(",");
			for (String volumeString : volumeStrings) {
				String[] extAndInt = volumeString.split(":");
				assert extAndInt.length == 2;
				volumes.put(new File(extAndInt[0]), extAndInt[1]);
			}
		}
	}

	public String createModelContainer(
			String imageReference,
			String containerName, 
			Map<File, String> roVolumes,
			Map<File, String> rwVolumes, 
			List<String> environmentVariables, 
			List<String> command,
			String workingDir) throws IOException {
		List<Bind> binds = new ArrayList<Bind>();
		String rwVolumeList = getProperty("RW_VOLUMES", false);
		if (!StringUtils.isEmpty(rwVolumeList)) {
			String[] volumeStrings = rwVolumeList.split(",");
			for (String volumeString : volumeStrings) {
				String[] extAndInt = volumeString.split(":");
				assert extAndInt.length == 2;
				binds.add(new Bind(extAndInt[0], new Volume(extAndInt[1]),
						AccessMode.rw));
			}
		}
		if (roVolumes != null) {
			for (File file : roVolumes.keySet()) {
				binds.add(new Bind(file.getAbsolutePath(), new Volume(roVolumes
						.get(file)), AccessMode.ro));
			}
		}
		if (rwVolumes != null) {
			for (File file : rwVolumes.keySet()) {
				binds.add(new Bind(file.getAbsolutePath(), new Volume(rwVolumes
						.get(file)), AccessMode.rw));
			}
		}
		List<Device> devices = new ArrayList<Device>();
		List<String> nvidiaDevices = new ArrayList<String>();
		List<String> env = new ArrayList<String>(environmentVariables);
		if (!nvidiaDevices.isEmpty()) {
			env.add("GPUS=" + StringUtils.join(nvidiaDevices, ";"));
		}
		return createContainer(imageReference, containerName,
				binds.toArray(new Bind[] {}), devices.toArray(new Device[] {}),
				command, env, workingDir);
	}

	private static final String UNKNOWN_MANIFEST_ERROR = "{\"message\":\"manifest unknown: manifest unknown\"}";

	private static final String IMAGE_TOO_BIG_MESSAGE_SEGMENT = "no space left on device";

	private static final String IMAGE_TOO_BIG_MESSAGE = "Docker pull failed because image is too large.";

	private static boolean isImageTooBigException(DockerClientException e) {
		return !StringUtils.isEmpty(e.getMessage()) &&
				e.getMessage().indexOf(IMAGE_TOO_BIG_MESSAGE_SEGMENT)>=0;
	}

	public void pullImageWithRetry(String imageReference) {
		for (int i = 0; i < MAX_RETRIES; i++) {
			String msg = "Unable to pull image " + imageReference + " after "
					+ (i + 1) + " try(ies).";
			try {
				PullImageResultCallback callback = null;
				if (DockerNameUtil.getRegistryHost(imageReference) == null) {
					// if not host then it's a DockerHub reference
					callback = dockerHubClient.pullImageCmd(imageReference)
							.exec(new PullImageResultCallback());
				} else { // TODO authenticate to quay.io
					callback = dockerClient.pullImageCmd(imageReference).exec(
							new PullImageResultCallback());
				}
				callback.awaitSuccess();
				return;
			} catch (InternalServerErrorException e) {
				if (e.getMessage().equals(UNKNOWN_MANIFEST_ERROR)) {
					throw new InternalServerErrorException(
							"Unable to pull the specified image.  Please check the repository and digest. "
									+ imageReference, e);
				} else {
					if (i >= MAX_RETRIES - 1)
						throw e;
				}
			} catch (DockerException e) {
				if (i >= MAX_RETRIES - 1)
					throw new DockerException(msg, e.getHttpStatus(), e);
			} catch (DockerClientException e) {
				if (isImageTooBigException(e)) throw new DockerPullException(IMAGE_TOO_BIG_MESSAGE, e);
				if (i >= MAX_RETRIES - 1)
					throw new DockerClientException(msg, e);
			}
			try {
				Thread.sleep(10000L);
			} catch (InterruptedException e) {
				throw new RuntimeException(msg, e);
			}
		}
	}

	public String createContainer(
			String imageReference, 
			String containerName,
			Bind[] binds, 
			Device[] devices, 
			List<String> cmd, 
			List<String> env,
			String workingDir)
					throws IOException {
		if (!imageReference.toLowerCase().startsWith("sha256:")) {
			try {
				pullImageWithRetry(imageReference);
			} catch (DockerException e) {
				throw new DockerPullException("Failed trying: docker pull "
						+ imageReference, e);
			} catch (DockerClientException e) {
				throw new DockerPullException("Failed trying: docker pull "
						+ imageReference, e);
			}
		}

		HostConfig hostConfig = new HostConfig()
				.withSecurityOpts(Collections.singletonList("no-new-privileges"));

		LogConfig logConfig = new LogConfig();
		logConfig.setType(LoggingType.JSON_FILE);
		Map<String,String> logConfigOptions = new HashMap<String,String>();
		logConfigOptions.put("max-size", "1g");
		logConfigOptions.put("max-file", "2");
		logConfig.setConfig(logConfigOptions);

		CreateContainerCmd command = dockerClient
				.createContainerCmd(imageReference)
				.withHostConfig(hostConfig)
				.withName(containerName)
				.withNetworkDisabled(false)
				.withMemorySwap(0L)
				.withLogConfig(logConfig);
		if (cmd != null) {
			command = command.withCmd(cmd);
		}
		if (workingDir != null) {
			command = command.withWorkingDir(workingDir);
		}
		command = command.withBinds(binds);
		command = command.withDevices(devices);
//		command = command.withCpusetCpus(getProperty("CPUS")).withMemory(
//				Long.parseLong(getProperty("MEMORY_GB")) * GIGABYTE_IN_BYTES);
		if (env != null)
			command = command.withEnv(env);

		CreateContainerResponse container = command.exec();

		return container.getId();
	}

	public void startContainer(String id) {
		dockerClient.startContainerCmd(id).exec();
	}

	public ContainerState getContainerState(String containerId) {
		InspectContainerResponse inspectContainerResponse = dockerClient
				.inspectContainerCmd(containerId).exec();
		return inspectContainerResponse.getState();
	}

	/*
	 * Create a temporary file having the content of the container's output
	 * Also return the very tail of the logs, if maxTailLength is not null
	 */
	public String getLogs(String containerId, Path outPath, Integer maxTailLengthCharacters) throws IOException {
		final OutputStream os = new FileOutputStream(outPath.toFile());
		try {
			LoggingResultsCallback resultCallback = new LoggingResultsCallback(
					os, Thread.currentThread(), maxTailLengthCharacters);
			dockerClient.logContainerCmd(containerId)
			.withStdErr(true)
			.withStdOut(true)
			.withTimestamps(true)
			.exec(resultCallback);
			while (!resultCallback.isComplete()) {
				try {
					Thread.sleep(1000L);
				} catch (InterruptedException e) {
					// callback has interrupted our sleep!
				}
			}
			return resultCallback.getTail();
		} finally {
			os.close();
		}
	}

	public String getLogsTail(String containerId, int numberOfLines)
			throws IOException {
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		try {
			LoggingResultsCallback resultCallback = new LoggingResultsCallback(
					os, Thread.currentThread(), null);
			dockerClient.logContainerCmd(containerId).withTimestamps(true)
			.withStdErr(true).withStdOut(true).withTail(numberOfLines)
			.exec(resultCallback);
			while (!resultCallback.isComplete()) {
				try {
					Thread.sleep(1000L);
				} catch (InterruptedException e) {
					// callback has interrupted our sleep!
				}
			}
		} finally {
			os.close();
		}
		return os.toString();
	}

	private static final ExponentialBackoffRunner STOP_CONTAINER_BACKOFF_RUNNER = 
			new ExponentialBackoffRunner(Collections.EMPTY_LIST, new Integer[] {}, MAX_CONTAINER_STOP_RETRIES);

	public void stopContainerWithRetry(String containerId) {
		try {
			STOP_CONTAINER_BACKOFF_RUNNER.execute(new NoRefreshExecutableAdapter<Void,Void> () {
				@Override
				public Void execute(Void args) throws Throwable {
					dockerClient.stopContainerCmd(containerId).withTimeout(60).exec();
					InspectContainerResponse inspectContainerResponse = dockerClient
							.inspectContainerCmd(containerId).exec();
					if (inspectContainerResponse.getState().getRunning())
						throw new RuntimeException("Failed to stop container.");
					return null;
				}}, null);
		} catch (Throwable e) {
			if (e instanceof RuntimeException) {
				throw (RuntimeException)e;
			} else {
				throw new RuntimeException(e);
			}
		}
	}

	public void removeContainer(String containerId, boolean force) {
		dockerClient.removeContainerCmd(containerId).withForce(force).exec();
	}

	public void renameContainer(String containerId, String newName) {
		dockerClient.renameContainerCmd(containerId).withName(newName).exec();
	}

	public void removeImage(String imageId, boolean force) {
		dockerClient.removeImageCmd(imageId).withForce(force).exec();
	}

	public void createVolume(String name, String driver) {
		dockerClient.createVolumeCmd().withName(name).withDriver(driver).exec();
	}

	/*
	 * Given a stopped container, take a snapshot and return the imageId
	 */
	public String commit(String containerId, String repo) {
		String imageId = dockerClient.commitCmd(containerId)
				.withRepository(repo).exec();
		return imageId;
	}

	/*
	 * Send a tagged image to the registry.
	 */
	public void pushImageToRepo(String imageId, String repo) {
		String registryAddress = null;
		String registryUsername = null;
		String registryPassword = null;
		String registryEmail = null;
		if (DockerNameUtil.getRegistryHost(repo) == null) {
			registryAddress = DOCKERHUB_REGISTRY_ADDRESS;
			registryUsername = getProperty(DOCKERHUB_USERNAME_PROPERTY);
			registryPassword = getProperty(DOCKERHUB_PASSWORD_PROPERTY);
			registryEmail = DOCKERHUB_EMAIL;
		} else {
			registryAddress = SYNAPSE_REGISTRY_ADDRESS;
			registryUsername = getProperty(SYNAPSE_USERNAME_PROPERTY);
			registryPassword = getProperty(SYNAPSE_PASSWORD_PROPERTY);
			registryEmail = SYNAPSE_EMAIL;
		}

		AuthConfig authConfig = new AuthConfig().withUsername(registryUsername)
				.withEmail(registryEmail).withRegistryAddress(registryAddress)
				.withPassword(registryPassword);


		dockerClient.tagImageCmd(imageId, repo, "latest").exec();

		PushImageResultCallback callback = dockerClient.pushImageCmd(imageId)
				.withName(repo).withAuthConfig(authConfig)
				.exec(new PushImageResultCallback());
		callback.awaitSuccess();
	}

	/*
	 * pass null for filter to return all containers
	 */
	public Map<String, Container> listContainers(Filter filter) {
		Map<String, Container> result = new HashMap<String, Container>();
		List<Container> containers = dockerClient.listContainersCmd()
				.withShowAll(true).exec();
		for (Container container : containers) {
			for (String name : container.getNames()) {
				// for some reason 'listContainers' prepends a leading "/" to
				// the assigned name
				String scrubbedName = name.startsWith("/") ? name.substring(1)
						: name;
				if (filter == null || filter.match(scrubbedName)) {
					result.put(scrubbedName, container);
				}
			}
		}
		return result;
	}

	public String exec(String containerId, String[] command) throws IOException {
		ExecCreateCmdResponse eccr = dockerClient.execCreateCmd(containerId).withCmd(command)
				.withAttachStderr(true).withAttachStdout(true).exec();
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		try {
			LoggingResultsCallback resultCallback = new LoggingResultsCallback(
					os, Thread.currentThread(), null);
			dockerClient.execStartCmd(eccr.getId()).exec(resultCallback);
			while (!resultCallback.isComplete()) {
				try {
					Thread.sleep(1000L);
				} catch (InterruptedException e) {
					// callback has interrupted our sleep!
				}
				if (os.size()>Constants.GIGABYTE_IN_BYTES) {
					log.warn("Command response has exceeded 1GB in size.  Will not collect any more data.");
					break;
				}
			}
		} finally {
			os.close();
		}
		return os.toString();
	}

	private static void checkHttpResponseCode(HttpResponse response, int expected) {
		if (expected!=response.getStatusLine().getStatusCode()) 
			throw new RuntimeException("Expected "+expected+" but received "+
					response.getStatusLine().getStatusCode());		
	}

	private static String getBearerTokenUrl(HttpResponse response) {
		String bearerRealm = null;
		String service = null;
		String scope = null;
		for (Header header : response.getAllHeaders()) {
			if (header.getName().equals("Www-Authenticate")) {
				for (HeaderElement element : header.getElements()) {
					if (element.getName().equals("Bearer realm")) bearerRealm=element.getValue();
					if (element.getName().equals("service")) service=element.getValue();
					if (element.getName().equals("scope")) scope=element.getValue();
				}
			}
		}
		if (bearerRealm==null) throw new RuntimeException("No 'bearerRealm'.");
		if (service==null) throw new RuntimeException("No 'service'.");
		if (scope==null) throw new RuntimeException("No 'scope'.");

		return bearerRealm+"?service="+service+"&scope="+scope;
	}

	private static JSONObject getResponseBodyAsJson(HttpResponse response) throws UnsupportedOperationException, IOException, JSONException {
		InputStream inputStream = response.getEntity().getContent();
		StringBuffer result = new StringBuffer();
		try {
			BufferedReader rd = new BufferedReader(
					new InputStreamReader(inputStream));

			String line = "";
			while ((line = rd.readLine()) != null) {
				result.append(line);
			}
		} finally {
			inputStream.close();
		}
		return new JSONObject(result.toString());
	}

	private static HttpClient getHttpClient() {
		return HttpClientBuilder.create().build();
	}

	private static String  getAuthToken(final HttpRequestBase request) throws UnsupportedOperationException, IOException, JSONException {
		// Step 1:  Send the unauthorized request and get the authorization request info in the response headers
		HttpResponse response = getHttpClient().execute(request);
		checkHttpResponseCode(response, HttpStatus.SC_UNAUTHORIZED);
		HttpEntity entity = response.getEntity();
		EntityUtils.consume(entity);
		String bearerTokenUrl = getBearerTokenUrl(response);

		// Step 2: Get the bearer token
		HttpGet bearerRequest = new HttpGet(bearerTokenUrl);

		String username;
		String password;
		if (request.getURI().getHost().equals(SYNAPSE_REGISTRY_ADDRESS)) {
			username = getProperty(SYNAPSE_USERNAME_PROPERTY);
			password = getProperty(SYNAPSE_PASSWORD_PROPERTY);
		} else if (request.getURI().getHost().equals(DOCKERHUB_REGISTRY_ADDRESS)) {
			username = getProperty(DOCKERHUB_USERNAME_PROPERTY);
			password = getProperty(DOCKERHUB_PASSWORD_PROPERTY);
		} else {
			throw new RuntimeException("Unexpected host: "+request.getURI().getHost());
		}
		bearerRequest.addHeader("Authorization", "Basic "+Base64.encodeAsString(username+":"+password));
		response = getHttpClient().execute(bearerRequest);

		checkHttpResponseCode(response, HttpStatus.SC_OK);

		JSONObject tokenJson = getResponseBodyAsJson(response);

		entity = response.getEntity();
		EntityUtils.consume(entity);

		return tokenJson.getString("token");
	}

	public JSONObject registryRequest(final HttpRequestBase request, final int expecteResponseCode) throws UnsupportedOperationException, IOException, JSONException {
		String token = getAuthToken(request);
		// Step 3: repeat the original request, this time with the bearer token
		request.addHeader("Authorization", "Bearer "+token);		
		HttpResponse response = getHttpClient().execute(request);
		checkHttpResponseCode(response, expecteResponseCode);
		return getResponseBodyAsJson(response);
	}

	/*
	 * An example of a valid input is:
	 * https://docker.synapse.org/v2/syn5644795/dm-trivial-model/blobs/sha256:144e61df6f8b7f7281e55c08a3d72530c612024abbb3d7b1182469cda01f7970
	 * 
	 * The returned value is that of compressed size.
	 */
	public long getCompressedLayerSize(final String layerRequest) throws UnsupportedOperationException, IOException, JSONException {
		HttpGet request = new HttpGet(layerRequest);
		String token = getAuthToken(request);

		HttpClient httpClient = HttpClientBuilder.create().disableRedirectHandling().build();
		HttpHead head = new HttpHead(layerRequest);
		// Step 3: repeat the original request, this time with the bearer token
		head.addHeader("Authorization", "Bearer "+token);			
		HttpResponse response = httpClient.execute(head);

		checkHttpResponseCode(response, HttpStatus.SC_TEMPORARY_REDIRECT);

		HttpEntity entity = response.getEntity();
		EntityUtils.consume(entity);

		String redirectUrl = response.getFirstHeader("Location").getValue();

		HttpHead redirectedRequest = new HttpHead(redirectUrl);
		response = getHttpClient().execute(redirectedRequest);
		checkHttpResponseCode(response, HttpStatus.SC_OK);

		entity = response.getEntity();
		EntityUtils.consume(entity);

		Long result = null;
		for (Header header : response.getAllHeaders()) {
			if (header.getName().equals("Content-Length")) result = Long.parseLong(header.getValue());
		}	
		return result;
	}

	public boolean doesImageExistAndIsAccessible(String image) throws InvalidSubmissionException, UnsupportedOperationException, IOException, JSONException {
		log.info("DockerUtils.doesImageExist: "+image);
		SubmissionUtils.validateDockerCommit(image);
		log.info("DockerUtils.doesImageExist: Image is syntactically correct.");
		int digestIndex = image.indexOf("@sha256:");
		String repoName = image.substring(0, digestIndex);
		String digest = image.substring(digestIndex+1);
		String registryHost;
		try {
			registryHost = DockerNameUtil.getRegistryHost(repoName);
		} catch (IllegalArgumentException e) {
			throw new InvalidSubmissionException(e);
		}

		String repoPath;
		if (registryHost==null) {
			registryHost = "index.docker.io";
			repoPath = repoName;
		} else if (registryHost.equals(SYNAPSE_REGISTRY_ADDRESS)) {
			repoPath = repoName.substring(SYNAPSE_REGISTRY_ADDRESS.length()+1, repoName.length());
		} else {
			throw new InvalidSubmissionException("Unexpected host name "+registryHost);
		}

		StringBuilder url = new StringBuilder("https://");
		url.append(registryHost);
		url.append("/v2/");
		url.append(repoPath);
		url.append("/manifests/"+digest);

		HttpGet request = new HttpGet(url.toString());
		String token = getAuthToken(request);
		request.addHeader("Authorization", "Bearer "+token);		
		HttpResponse response = getHttpClient().execute(request);

		log.info("DockerUtils.doesImageExist: Registry returned status code "+response.getStatusLine().getStatusCode());

		switch(response.getStatusLine().getStatusCode()) {
		case HttpStatus.SC_OK:
			return true;
		case HttpStatus.SC_NOT_FOUND:
			return false;
		case HttpStatus.SC_UNAUTHORIZED:
			return false;
		default:
			throw new RuntimeException("Unexpected HTTP status: "+response.getStatusLine().getStatusCode());
		}
	}

	public long getCompressedImageSize(String image) throws UnsupportedOperationException, IOException, JSONException, InvalidSubmissionException {
		SubmissionUtils.validateDockerCommit(image);
		int digestIndex = image.indexOf("@sha256:");
		String repoName = image.substring(0, digestIndex);
		String digest = image.substring(digestIndex+1);
		String registryHost = DockerNameUtil.getRegistryHost(repoName);

		String repoPath;
		if (registryHost==null) {
			registryHost = "index.docker.io";
			repoPath = repoName;
		} else if (registryHost.equals(SYNAPSE_REGISTRY_ADDRESS)) {
			repoPath = repoName.substring(SYNAPSE_REGISTRY_ADDRESS.length()+1, repoName.length());
		} else {
			throw new RuntimeException("Unexpected host "+registryHost);
		}

		StringBuilder url = new StringBuilder("https://");
		url.append(registryHost);
		url.append("/v2/");
		url.append(repoPath);
		url.append("/manifests/"+digest);

		HttpGet request = new HttpGet(url.toString());

		JSONObject responseJson = registryRequest(request, HttpStatus.SC_OK);

		//
		// This does not appear in the documentation https://docs.docker.com/registry/spec/api/, but
		// if the reference is given as a *tag* then the response contains a list of layers under 'fsLayers',
		// and sizes are not given, however if the refernece is given as a *digest* then the resoonse
		// contains a list of layers under 'layers' and sizes ARE given with no further requests needed.
		// In either case the size appears to be the compressed layer size.
		//

		if (responseJson.has("fsLayers")) {
			JSONArray fsLayers = (JSONArray)responseJson.get("fsLayers");

			StringBuilder layerUrlBuilder = new StringBuilder("https://");
			layerUrlBuilder.append(registryHost);
			layerUrlBuilder.append("/v2/");
			layerUrlBuilder.append(repoPath);
			layerUrlBuilder.append("/blobs/");
			String layerUrlPrefix = layerUrlBuilder.toString();

			long total = 0L;
			for (int i=0; i<fsLayers.length(); i++) {
				JSONObject layer = fsLayers.getJSONObject(i);
				long layerSize = getCompressedLayerSize(layerUrlPrefix+layer.getString("blobSum"));
				total += layerSize;
			}
			return total;
		} else if (responseJson.has("layers")) {
			JSONArray layers = (JSONArray)responseJson.get("layers");
			long total = 0L;
			for (int i=0; i<layers.length(); i++) {
				JSONObject layer = layers.getJSONObject(i);
				long layerSize =layer.getLong("size");
				total += layerSize;
			}
			return total;
		} else {
			throw new RuntimeException("Manifest has neither 'layers' nor 'fsLayers' key.");
		}
	}
}
