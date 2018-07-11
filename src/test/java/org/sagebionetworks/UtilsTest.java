package org.sagebionetworks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.sagebionetworks.repo.model.EntityBundle;
import org.sagebionetworks.repo.model.docker.DockerRepository;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;


public class UtilsTest {

	@Test
	public void testGetDockerRepositoryNameFromEntityBundle() throws Exception {
		String repoName = "docker.synapse.org/syn1234/foo";
		EntityBundle bundle = new EntityBundle();
		DockerRepository repoEntity = new DockerRepository();
		repoEntity.setRepositoryName(repoName);
		bundle.setEntity(repoEntity);
		JSONObjectAdapter joa = new JSONObjectAdapterImpl();
		bundle.writeToJSONObject(joa);
		assertEquals(repoName, Utils.getDockerRepositoryNameFromEntityBundle(joa.toJSONString()));
	}

	@Test
	public void testGetProgressFromString() {
		assertEquals(92.85D, Utils.getProgressPercentFromString("STDOUT: 92.85714285714286", 5), 1e-5);
	}

}
