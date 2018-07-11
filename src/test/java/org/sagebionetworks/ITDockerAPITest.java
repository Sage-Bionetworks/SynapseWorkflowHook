package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.sagebionetworks.Constants.SYNAPSE_PASSWORD_PROPERTY;
import static org.sagebionetworks.Constants.SYNAPSE_USERNAME_PROPERTY;
import static org.sagebionetworks.Utils.getProperty;

import org.junit.Before;
import org.junit.Test;

public class ITDockerAPITest {
	private String userName;
	private String password;

	@Before
	public void setUp() throws Exception {
		userName=null;
		password=null;
		userName = getProperty(SYNAPSE_USERNAME_PROPERTY, false);
		password = getProperty(SYNAPSE_PASSWORD_PROPERTY, false);
		assumeNotNull(userName);
		assumeNotNull(password);
	}


	@Test
	public void testGetCompressedImages() throws Exception {
		assumeNotNull(userName);
		assumeNotNull(password);
		DockerUtils dockerUtils = new DockerUtils();

		assertEquals(49833194, 
				dockerUtils.getCompressedImageSize("docker.synapse.org/syn5644795/dm-trivial-model@sha256:b2f5b54559d62aa7a0bb947da161c9c358028a359a84adaa4a8213e709ecbf20"));

		assertEquals(255879920,
				dockerUtils.getCompressedImageSize("dreamchall/r_with_synapse_client@sha256:a7e66c956ae76dc6d763d056014729ae8d476de8e88f391bf5821f90dcadcdfc"));
	}

	@Test
	public void testDoesImageExist() throws Exception {
		assumeNotNull(userName);
		assumeNotNull(password);
		DockerUtils dockerUtils = new DockerUtils();

		String realRepository = "docker.synapse.org/syn5644795/dm-trivial-model";
		String realDigest = "sha256:b2f5b54559d62aa7a0bb947da161c9c358028a359a84adaa4a8213e709ecbf20";

		assertTrue(dockerUtils.doesImageExistAndIsAccessible(realRepository+"@"+realDigest));

		String wrongDigest = "sha256:a7e66c956ae76dc6d763d056014729ae8d476de8e88f391bf5821f90dcadcdfc";

		assertFalse(dockerUtils.doesImageExistAndIsAccessible(realRepository+"@"+wrongDigest));

		assertTrue(dockerUtils.doesImageExistAndIsAccessible("dreamchall/r_with_synapse_client@sha256:a7e66c956ae76dc6d763d056014729ae8d476de8e88f391bf5821f90dcadcdfc"));

		assertFalse(dockerUtils.doesImageExistAndIsAccessible("docker.synapse.org/syn7823455/dpt8@sha256:9fca3caf3aca79310571e776a73ef21b297fc9dc971be2582a2e673bcab61503"));

	}

}
