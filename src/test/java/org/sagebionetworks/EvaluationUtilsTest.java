package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.Constants.EXECUTION_STAGE_PROPERTY_NAME;

import org.junit.Test;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;


public class EvaluationUtilsTest {

	@Test
	public void testGetInitialSubmissionState() throws Exception {
		System.setProperty(EXECUTION_STAGE_PROPERTY_NAME, "");
		assertEquals(SubmissionStatusEnum.RECEIVED, EvaluationUtils.getInitialSubmissionState());
	}


}
