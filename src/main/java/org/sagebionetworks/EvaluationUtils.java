package org.sagebionetworks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseConflictingUpdateException;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.evaluation.model.BatchUploadResponse;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusBatch;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.annotation.Annotations;
import org.sagebionetworks.repo.model.annotation.DoubleAnnotation;
import org.sagebionetworks.repo.model.annotation.LongAnnotation;
import org.sagebionetworks.repo.model.annotation.StringAnnotation;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import static org.sagebionetworks.Constants.*;
import static org.sagebionetworks.Utils.*;

public class EvaluationUtils {
	private static final int PAGE_SIZE = 10;
	private static final int BATCH_UPLOAD_RETRY_COUNT = 3;

	public static final String JOB_STARTED_TIME_STAMP = "EXECUTION_STARTED";
	private static final String TIME_REMAINING = "TIME_REMAINING";
	public static final String FAILURE_REASON = "FAILURE_REASON";
	public static final String SUBMISSION_ARTIFACTS_FOLDER = "SUBMISSION_FOLDER";
	public static final String JOB_LAST_UPDATED_TIME_STAMP = "WORKFLOW_LAST_UPDATED";
	public static final String STATUS_DESCRIPTION = "STATUS_DESCRIPTION";
	public static final boolean ADMIN_ANNOTS_ARE_PRIVATE = true;
	public static final String USER_ID_PUBLIC = "USER_ID";
	public static final String LOG_FILE_SIZE_EXCEEDED = "LOG_FILE_SIZE_EXCEEDED";
	public static final String LAST_LOG_UPLOAD = "LAST_LOG_UPLOAD";
	public static final String LOG_FILE_NOTIFICATION_SENT = "LOG_FILE_NOTIFICATION_SENT";
	public static final String PROGRESS = "PROGRESS";

	//annotation values
	public static final String STOPPED_UPON_REQUEST_ANNOTATION_VALUE = "STOPPED UPON REQUEST";
	public static final String DOCKER_PULL_FAILED_ANNOTATION_VALUE = "DOCKER PULL FAILED";
	public static final String ERROR_DURING_EXECUTION_ANNOTATION_VALUE = "ERROR";
	public static final String STOPPED_TIME_OUT_ANNOTATION_VALUE = "EXCEEDED TIME QUOTA";
	public static final String STATUS_ACCEPTED_ANNOTATION_VALUE = "COMPLETED";
	public static final String STATUS_EVAL_IN_PROGRESS_ANNOTATION_VALUE = "IN PROGRESS";

	// for public annotations, set isPrivate to false
	public static final boolean PUBLIC_ANNOTATION_SETTING = false;

	private SynapseClient synapse;

	public EvaluationUtils(SynapseClient synapse) {
		this.synapse=synapse;
	}
	
	enum EXECUTION_STAGE {
		VALIDATION,
		EXECUTION,
		ALL
	};
	
	public static SubmissionStatusEnum getInitialSubmissionState() {
		String executionStageString = getProperty(EXECUTION_STAGE_PROPERTY_NAME, false);
		EXECUTION_STAGE stage = executionStageString==null ? EXECUTION_STAGE.ALL : EXECUTION_STAGE.valueOf(executionStageString);
		switch (stage) {
		case VALIDATION:
			return SubmissionStatusEnum.RECEIVED;
		case EXECUTION:
			return SubmissionStatusEnum.VALIDATED;
		default: // ALL
			return SubmissionStatusEnum.RECEIVED;
		}
	}

	public static SubmissionStatusEnum getInProgressSubmissionState() {
		String executionStageString = getProperty(EXECUTION_STAGE_PROPERTY_NAME, false);
		EXECUTION_STAGE stage = executionStageString==null ? EXECUTION_STAGE.ALL : EXECUTION_STAGE.valueOf(executionStageString);
		switch (stage) {
		case VALIDATION:
			return SubmissionStatusEnum.OPEN;
		case EXECUTION:
			return SubmissionStatusEnum.EVALUATION_IN_PROGRESS;
		default: // ALL
			return SubmissionStatusEnum.EVALUATION_IN_PROGRESS;
		}
	}

	public static SubmissionStatusEnum getFinalSubmissionState() {
		String executionStageString = getProperty(EXECUTION_STAGE_PROPERTY_NAME, false);
		EXECUTION_STAGE stage = executionStageString==null ? EXECUTION_STAGE.ALL : EXECUTION_STAGE.valueOf(executionStageString);
		switch (stage) {
		case VALIDATION:
			return SubmissionStatusEnum.VALIDATED;
		case EXECUTION:
			return SubmissionStatusEnum.ACCEPTED;
		default: // ALL
			return SubmissionStatusEnum.ACCEPTED;
		}
	}

	public static String getStringAnnotation(SubmissionStatus status, String key) {
		Annotations annotations = status.getAnnotations();
		if (annotations==null) return null;
		List<StringAnnotation> sas = annotations.getStringAnnos();
		if (sas==null) return null;
		for (StringAnnotation sa : sas) {
			if (sa.getKey().equals(key)) return sa.getValue();
		}
		return null;
	}

	public static Long getLongAnnotation(SubmissionStatus status, String key) {
		Annotations annotations = status.getAnnotations();
		if (annotations==null) return null;
		List<LongAnnotation> las = annotations.getLongAnnos();
		if (las==null) return null;
		for (LongAnnotation la : las) {
			if (la.getKey().equals(key)) return la.getValue();
		}
		return null;
	}

	/*
	 * return true iff the (string) annotation is present and is 'true' (case insensitive)
	 */
	public static boolean isBooleanAnnotationPresentAndTrue(SubmissionStatus status, String key) {
		Annotations annotations = status.getAnnotations();
		if (annotations==null) return false;
		List<StringAnnotation> sas = annotations.getStringAnnos();
		if (sas==null) return false;
		for (StringAnnotation sa : sas) {
			if (sa.getKey().equals(key)) return new Boolean(sa.getValue());
		}
		return false;
	}

	public static void setAnnotation(SubmissionStatus status, String key, String value, boolean isPrivate) {
		if (value!=null && value.length()>499) value = value.substring(0, 499);
		Annotations annotations = status.getAnnotations();
		if (annotations==null) {
			annotations=new Annotations();
			status.setAnnotations(annotations);
		}
		List<StringAnnotation> sas = annotations.getStringAnnos();
		if (sas==null) {
			sas = new ArrayList<StringAnnotation>();
			annotations.setStringAnnos(sas);
		}
		StringAnnotation matchingSa = null;
		for (StringAnnotation existingSa : sas) {
			if (existingSa.getKey().equals(key)) {
				matchingSa = existingSa;
				break;
			}
		}
		if (matchingSa==null) {
			StringAnnotation sa = new StringAnnotation();
			sa.setIsPrivate(isPrivate);
			sa.setKey(key);
			sa.setValue(value);
			sas.add(sa);
		} else {
			matchingSa.setIsPrivate(isPrivate);
			matchingSa.setValue(value);
		}
	}

	public static void setAnnotation(SubmissionStatus status, String key, long value, boolean isPrivate) {
		Annotations annotations = status.getAnnotations();
		if (annotations==null) {
			annotations=new Annotations();
			status.setAnnotations(annotations);
		}
		List<LongAnnotation> las = annotations.getLongAnnos();
		if (las==null) {
			las = new ArrayList<LongAnnotation>();
			annotations.setLongAnnos(las);
		}
		LongAnnotation matchingLa = null;
		for (LongAnnotation existingLa : las) {
			if (existingLa.getKey().equals(key)) {
				matchingLa = existingLa;
				break;
			}
		}
		if (matchingLa==null) {
			LongAnnotation la = new LongAnnotation();
			la.setIsPrivate(isPrivate);
			la.setKey(key);
			la.setValue(value);
			las.add(la);
		} else {
			matchingLa.setIsPrivate(isPrivate);
			matchingLa.setValue(value);
		}
	}

	public static void setAnnotation(SubmissionStatus status, String key, double value, boolean isPrivate) {
		Annotations annotations = status.getAnnotations();
		if (annotations==null) {
			annotations=new Annotations();
			status.setAnnotations(annotations);
		}
		List<DoubleAnnotation> das = annotations.getDoubleAnnos();
		if (das==null) {
			das = new ArrayList<DoubleAnnotation>();
			annotations.setDoubleAnnos(das);
		}
		DoubleAnnotation matchingDa = null;
		for (DoubleAnnotation existingDa : das) {
			if (existingDa.getKey().equals(key)) {
				matchingDa = existingDa;
				break;
			}
		}
		if (matchingDa==null) {
			DoubleAnnotation da = new DoubleAnnotation();
			da.setIsPrivate(isPrivate);
			da.setKey(key);
			da.setValue(value);
			das.add(da);
		} else {
			matchingDa.setIsPrivate(isPrivate);
			matchingDa.setValue(value);
		}
	}

	public static void setStatus(SubmissionStatus ss, SubmissionStatusEnum status, WorkflowUpdateStatus containerStatus) {
		ss.setStatus(status);
		switch(status) {
		case EVALUATION_IN_PROGRESS:
			setAnnotation(ss, STATUS_DESCRIPTION, STATUS_EVAL_IN_PROGRESS_ANNOTATION_VALUE, PUBLIC_ANNOTATION_SETTING);
			ss.setCanCancel(true);
			break;
		case ACCEPTED:
			setAnnotation(ss, STATUS_DESCRIPTION, STATUS_ACCEPTED_ANNOTATION_VALUE, PUBLIC_ANNOTATION_SETTING);
			ss.setCanCancel(false);
			break;
		case CLOSED:
			if (containerStatus==null) {
				setAnnotation(ss, STATUS_DESCRIPTION, status.name(), PUBLIC_ANNOTATION_SETTING);
			} else {
				switch (containerStatus) {
				case DOCKER_PULL_FAILED:
					setAnnotation(ss, STATUS_DESCRIPTION, DOCKER_PULL_FAILED_ANNOTATION_VALUE, PUBLIC_ANNOTATION_SETTING);
					break;
				case ERROR_ENCOUNTERED_DURING_EXECUTION:
					setAnnotation(ss, STATUS_DESCRIPTION, ERROR_DURING_EXECUTION_ANNOTATION_VALUE, PUBLIC_ANNOTATION_SETTING);
					break;
				case STOPPED_UPON_REQUEST:
					setAnnotation(ss, STATUS_DESCRIPTION, STOPPED_UPON_REQUEST_ANNOTATION_VALUE, PUBLIC_ANNOTATION_SETTING);
					break;
				case STOPPED_TIME_OUT:
					setAnnotation(ss, STATUS_DESCRIPTION, STOPPED_TIME_OUT_ANNOTATION_VALUE, PUBLIC_ANNOTATION_SETTING);
					break;
				default:
					setAnnotation(ss, STATUS_DESCRIPTION, containerStatus.name(), PUBLIC_ANNOTATION_SETTING);
				}
			}
			ss.setCanCancel(false);
			break;
		default:
			if (containerStatus==null) {
				setAnnotation(ss, STATUS_DESCRIPTION, status.name(), PUBLIC_ANNOTATION_SETTING);
			} else {
				setAnnotation(ss, STATUS_DESCRIPTION, containerStatus.name(), PUBLIC_ANNOTATION_SETTING);      			
			}
			ss.setCanCancel(false);    		
		}
	}

	public static void removeAnnotation(SubmissionStatus status, String key) {
		Annotations annotations = status.getAnnotations();
		if (annotations==null) return;

		List<StringAnnotation> sas = annotations.getStringAnnos();
		if (sas!=null) {
			for (Iterator<StringAnnotation> iterator = sas.iterator(); iterator.hasNext();) {
				StringAnnotation existing = iterator.next();
				if (existing.getKey().equals(key)) {
					iterator.remove();
				}
			}
		}

		List<DoubleAnnotation> das = annotations.getDoubleAnnos();
		if (das!=null) {
			for (Iterator<DoubleAnnotation> iterator = das.iterator(); iterator.hasNext();) {
				DoubleAnnotation existing = iterator.next();
				if (existing.getKey().equals(key)) {
					iterator.remove();
				}
			}
		}

		List<LongAnnotation> las = annotations.getLongAnnos();
		if (las!=null) {
			for (Iterator<LongAnnotation> iterator = las.iterator(); iterator.hasNext();) {
				LongAnnotation existing = iterator.next();
				if (existing.getKey().equals(key)) {
					iterator.remove();
				}
			}
		}
	}

	/**
	 * 
	 * @throws SynapseException
	 * @throws IOException
	 * @throws JSONObjectAdapterException
	 */
	public List<SubmissionBundle> selectSubmissions(String evaluationId, SubmissionStatusEnum state) throws SynapseException, IOException, JSONObjectAdapterException {
		long total = Integer.MAX_VALUE;
		List<SubmissionBundle> result = new ArrayList<SubmissionBundle>();
		Set<String> ids = new HashSet<String>();
		for (int offset=0; offset<total; offset+=PAGE_SIZE) {
			PaginatedResults<SubmissionBundle> submissionPGs = null;
			submissionPGs = synapse.getAllSubmissionBundlesByStatus(evaluationId, 
					state, offset, PAGE_SIZE);
			total = (int)submissionPGs.getTotalNumberOfResults();
			List<SubmissionBundle> page = submissionPGs.getResults();
			for (int i=0; i<page.size(); i++) {
				SubmissionBundle bundle = page.get(i);
				result.add(bundle);
				String id = bundle.getSubmission().getId();
				if (ids.contains(id)) throw new RuntimeException("List has multiple copies of "+id);
				ids.add(id);
				if (!bundle.getSubmissionStatus().getStatus().equals(state))
					throw new RuntimeException("Submission "+id+" has state "+bundle.getSubmissionStatus().getStatus()+" when "+state+" was expected.");
			}
		}
		return result;
	}

	public void updateSubmissionStatusBatch(List<SubmissionStatus> statusesToUpdate, String evaluationId) throws SynapseException {
		// now we have a batch of statuses to update
		for (int retry=0; retry<BATCH_UPLOAD_RETRY_COUNT; retry++) {
			try {
				String batchToken = null;
				for (int offset=0; offset<statusesToUpdate.size(); offset+=PAGE_SIZE) {
					SubmissionStatusBatch updateBatch = new SubmissionStatusBatch();
					List<SubmissionStatus> batch = new ArrayList<SubmissionStatus>();
					for (int i=0; i<PAGE_SIZE && offset+i<statusesToUpdate.size(); i++) {
						batch.add(statusesToUpdate.get(offset+i));
					}
					updateBatch.setStatuses(batch);
					boolean isFirstBatch = (offset==0);
					updateBatch.setIsFirstBatch(isFirstBatch);
					boolean isLastBatch = (offset+PAGE_SIZE)>=statusesToUpdate.size();
					updateBatch.setIsLastBatch(isLastBatch);
					updateBatch.setBatchToken(batchToken);
					BatchUploadResponse response = 
							synapse.updateSubmissionStatusBatch(evaluationId, updateBatch);
					batchToken = response.getNextUploadToken();
				}
				break; // success!
			} catch (SynapseConflictingUpdateException e) {
				// we collided with someone else access the Evaluation.  Will retry!
			}
		}
	}

	private static String formatInterval(final long l) {
		final long hr = TimeUnit.MILLISECONDS.toHours(l);
		final long min = TimeUnit.MILLISECONDS.toMinutes(l - TimeUnit.HOURS.toMillis(hr));
		final long sec = TimeUnit.MILLISECONDS.toSeconds(l - TimeUnit.HOURS.toMillis(hr) - TimeUnit.MINUTES.toMillis(min));
		final long ms = TimeUnit.MILLISECONDS.toMillis(l - TimeUnit.HOURS.toMillis(hr) - TimeUnit.MINUTES.toMillis(min) - TimeUnit.SECONDS.toMillis(sec));
		return String.format("%02dh:%02dm:%02d.%03ds", hr, min, sec, ms);
	}

	public static Long getTimeRemaining(SubmissionStatus ss) {
		return getLongAnnotation(ss, TIME_REMAINING);
	}

	/*
	 * If time remaining is missing then return true, i.e. the submission is
	 * enabled by default
	 */
	public static boolean hasTimeRemaining(Long timeRemaining) {
		return timeRemaining==null || timeRemaining>0L;
	}
}
