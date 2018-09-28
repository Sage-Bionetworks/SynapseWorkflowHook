package org.sagebionetworks;

import static org.sagebionetworks.Constants.DEFAULT_NUM_RETRY_ATTEMPTS;
import static org.sagebionetworks.Constants.NO_RETRY_EXCEPTIONS;
import static org.sagebionetworks.Constants.NO_RETRY_STATUSES;
import static org.sagebionetworks.EvaluationUtils.FAILURE_REASON;
import static org.sagebionetworks.EvaluationUtils.JOB_LAST_UPDATED_TIME_STAMP;
import static org.sagebionetworks.EvaluationUtils.PUBLIC_ANNOTATION_SETTING;
import static org.sagebionetworks.EvaluationUtils.applyModifications;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseConflictingUpdateException;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseForbiddenException;
import org.sagebionetworks.client.exceptions.SynapseLockedException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.client.exceptions.SynapseServerException;
import org.sagebionetworks.client.exceptions.SynapseServiceUnavailable;
import org.sagebionetworks.client.exceptions.SynapseTableUnavailableException;
import org.sagebionetworks.client.exceptions.SynapseTooManyRequestsException;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionContributor;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.EntityBundle;
import org.sagebionetworks.repo.model.EntityHeader;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.Team;
import org.sagebionetworks.repo.model.TeamMember;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.util.DockerNameUtil;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SubmissionUtils {

	private static Logger log = LoggerFactory.getLogger(SubmissionUtils.class);

	// commit must have the form reponame@sha256:digest, where digest is 64 hex chars

	// a valid name with a trailing '/' appended will cause this regex to hang forever
	private static final String DOCKER_NAME_REGEX = "^"+DockerNameUtil.NameRegexp+"$";

	private static final String DOCKER_DIGEST_REGEX = "^sha256:[0-9a-f]{64}$";

	public static final String SYNID_REGEX = "syn[0-9]+";

	private static final String AUTH_USERS_PRINCIPAL_ID = "273948";
	private static final String PUBLIC_PRINCIPAL_ID = "273949";

	private SynapseClient synapse;

	public SubmissionUtils(SynapseClient synapse) {
		this.synapse=synapse;
	}
	
	private static final ExponentialBackoffRunner SUBMISSION_STATUS_UPDATE_RUNNER;
	static {
		List<Class<? extends SynapseServerException>> noRetryExceptions = new ArrayList<Class<? extends SynapseServerException>>(NO_RETRY_EXCEPTIONS);
		// this is the one exception we DO want to retry on!
		boolean foundit = noRetryExceptions.remove(SynapseConflictingUpdateException.class);
		// these are being retried on the lower level so we do NOT want to retry on them here too
		noRetryExceptions.add(SynapseServiceUnavailable.class);
		noRetryExceptions.add(SynapseTooManyRequestsException.class);
		noRetryExceptions.add(SynapseLockedException.class);
		noRetryExceptions.add(SynapseTableUnavailableException.class);
		
		SUBMISSION_STATUS_UPDATE_RUNNER = new ExponentialBackoffRunner(noRetryExceptions, NO_RETRY_STATUSES, DEFAULT_NUM_RETRY_ATTEMPTS);
	}
	
	public SubmissionStatus updateSubmissionStatus(SubmissionStatus submissionStatus, SubmissionStatusModifications statusMods) throws SynapseException {
		try {
			return SUBMISSION_STATUS_UPDATE_RUNNER.execute(new Executable<SubmissionStatus,SubmissionStatus>(){
				public SubmissionStatus execute(SubmissionStatus status) throws SynapseException {
					applyModifications(status, statusMods);
					return synapse.updateSubmissionStatus(status);
				}
				public SubmissionStatus refreshArgs(SubmissionStatus status) throws SynapseException {
					return synapse.getSubmissionStatus(status.getId());
				}
			}, submissionStatus);
		} catch (SynapseException s) {
			throw s;
		} catch (RuntimeException e) {
			throw e;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public void closeSubmissionAndSendNotification(
			final String messageRecipientId, 
			final SubmissionStatus ss, 
			final SubmissionStatusModifications statusMods,
			final SubmissionStatusEnum status,
			final WorkflowUpdateStatus containerStatus,
			final Throwable t,
			final String messageSubject,
			final String messageBody) throws Throwable {
		EvaluationUtils.setStatus(statusMods, status, containerStatus);
		if (t==null) {
			EvaluationUtils.removeAnnotation(statusMods, FAILURE_REASON);
		} else {
			EvaluationUtils.setAnnotation(statusMods, FAILURE_REASON, t.getMessage(), PUBLIC_ANNOTATION_SETTING);
		}
		EvaluationUtils.setAnnotation(statusMods, JOB_LAST_UPDATED_TIME_STAMP, System.currentTimeMillis(), false);
		updateSubmissionStatus(ss, statusMods);
		(new MessageUtils(synapse)).sendMessage(messageRecipientId, messageSubject,  messageBody);
	}

	public static void validateDockerCommit(String s) throws InvalidSubmissionException {
		log.info("validating: "+s);
		String[] repoAndDigest = s.split("@");
		// make sure there's a repo followed by a digest
		if (repoAndDigest.length!=2 || 
				StringUtils.isEmpty(repoAndDigest[0]) ||
				repoAndDigest[0].endsWith("/") ||
				!Pattern.matches(DOCKER_NAME_REGEX, repoAndDigest[0]) ||
				!Pattern.matches(DOCKER_DIGEST_REGEX, repoAndDigest[1])) 
			throw new InvalidSubmissionException(s+" is not a valid Docker commit.  Must be [host/]path@sha256:digest");
		log.info("done validating");
	}

	public static void validateSynapseId(String s) throws InvalidSubmissionException {
		if (!Pattern.matches(SYNID_REGEX, s.toLowerCase().trim()))
			throw new InvalidSubmissionException(s+" is not a Synapse ID.");
	}

	// docker.synapse.org/syn123/foo/bar@sha256:...  ->  foo/bar
	public static String getRepoSuffixFromImage(String s) {
		int at = s.indexOf("@");
		String repoWithoutDigest = at<0 ? s : s.substring(0, at);
		String host = DockerNameUtil.getRegistryHost(repoWithoutDigest);
		String repoPath = StringUtils.isEmpty(host) ? repoWithoutDigest : repoWithoutDigest.substring(host.length());
		if (repoPath.startsWith("/")) repoPath = repoPath.substring(1);
		String[] pathSegments = repoPath.split("/");
		if (pathSegments.length<2) return repoPath;
		if (!Pattern.matches(SYNID_REGEX, pathSegments[0])) return repoPath;
		StringBuilder sb = new StringBuilder();
		for (int i=1; i<pathSegments.length; i++) {
			if (i>1) sb.append("/");
			sb.append(pathSegments[i]);
		}
		return sb.toString();
	}

	public static String getRepoNameForDockerImage(String image) {
		int i = image.indexOf("@sha256:");
		return image.substring(0, i);
	}

	/*
	 * returns the project Id for a Synapse Docker image.  If it's a valid commit but
	 * not a valid commit for the Synapse registry, then returns null.  
	 * If it refers to the Synapse registry but the path is not valid (synapseId/name),
	 * then throws InvalidSubmissionException
	 */
	public static String getSynapseProjectIdForDockerImage(String image) throws InvalidSubmissionException {
		validateDockerCommit(image);
		String repo = getRepoNameForDockerImage(image);
		return getSynapseProjectIdForRepoName(repo);
	}

	public static String getSynapseProjectIdForRepoName(String repo) throws InvalidSubmissionException {
		String hostPrefix = DockerNameUtil.getRegistryHost(repo);
		if (hostPrefix==null) return null; // it's not a Synapse Docker image
		if (!hostPrefix.equals(Utils.SYNAPSE_DOCKER_HOST)) return null; // it's not a Synapse Docker image
		String repoPath = repo.substring(hostPrefix.length()+1);
		String[] pathElements = repoPath.split("/");
		if (pathElements.length<1) throw new InvalidSubmissionException("Not a valid Synapse Docker repository name: "+repo);
		String synapseId = pathElements[0];
		if (!Pattern.matches(SYNID_REGEX, synapseId)) throw new InvalidSubmissionException("Not a valid Synapse Docker repository name: "+repo);
		return synapseId;
	};

	private static final long TEAM_MEMBERS_PAGE_SIZE = 50;

	/*
	 * At least one of the contributors must have READ access to the entity
	 */
	public void validateEntityAccessGivenEntityId(String entityId, Collection<String> contributors) throws SynapseException, InvalidSubmissionException {
		// get entity benefactor
		EntityHeader benefactor;
		try {
			benefactor = synapse.getEntityBenefactor(entityId);
		} catch (SynapseForbiddenException e) {
			throw new InvalidSubmissionException("Entity "+entityId+" is not acessible by the workflow infrastructure. Please check the sharing settings.", e);
		} catch (SynapseNotFoundException e) {
			throw new InvalidSubmissionException("Entity "+entityId+" does not exist.", e);
		}
		validateEntityAccessGivenBenefactorId(benefactor.getId(), entityId, contributors);
	}

	public void validateEntityAccessGivenBenefactorId(String benefactorId, String entityReference, Collection<String> contributors) throws SynapseException, InvalidSubmissionException {
		// get entity ACL
		AccessControlList acl = synapse.getACL(benefactorId);
		// get membership in all the Teams in the ACL ('explode' the ACL)
		// list the users that have READ access
		List<String> usersHavingReadAccess = new ArrayList<String>();
		for (ResourceAccess ra : acl.getResourceAccess()) {
			if (ra.getAccessType().contains(ACCESS_TYPE.READ)) {
				Long principalId = ra.getPrincipalId();
				for (long offset=0, totalNumberOfResults = 1000L; offset<totalNumberOfResults; offset+=TEAM_MEMBERS_PAGE_SIZE) {
					PaginatedResults<TeamMember> members = synapse.getTeamMembers(""+principalId, null, TEAM_MEMBERS_PAGE_SIZE, offset);
					totalNumberOfResults = members.getTotalNumberOfResults();
					if (members.getTotalNumberOfResults()==0L) {
						// so it's a user, not a team
						usersHavingReadAccess.add(""+principalId);
					} else {
						for (TeamMember member : members.getResults()) {
							usersHavingReadAccess.add(member.getMember().getOwnerId());
						}					
					}
				}
			}
		}
		if (usersHavingReadAccess.contains(AUTH_USERS_PRINCIPAL_ID) || 
				usersHavingReadAccess.contains(PUBLIC_PRINCIPAL_ID)) return;
		usersHavingReadAccess.retainAll(contributors);
		// if there is any overlap between the contributors and the users having READ access 
		usersHavingReadAccess.retainAll(contributors);
		if (usersHavingReadAccess.isEmpty()) 
			throw new InvalidSubmissionException(entityReference+" is not accessible by the submission creators.");
	}

	public static Class<Entity> getEntityTypeFromSubmission(Submission sub) throws JSONObjectAdapterException {
		EntityBundle bundle = EntityFactory.createEntityFromJSONString(
				sub.getEntityBundleJSON(), EntityBundle.class);
		return (Class<Entity>)bundle.getEntity().getClass();
	}

	public static String getSubmittingUserOrTeamId(Submission submission) {
		return submission.getTeamId()==null ? submission.getUserId(): submission.getTeamId();    	
	}
	
	private Map<String,String> idToNameCache = new HashMap<String,String>();

	public Submitter getSubmitter(Submission sub) throws SynapseException {
		String submittingUserOrTeamId;
		String submittingUserOrTeamName;
		if (sub.getTeamId()==null) {
			submittingUserOrTeamId = sub.getUserId();
			submittingUserOrTeamName=idToNameCache.get(submittingUserOrTeamId);
			if (submittingUserOrTeamName==null) {
				UserProfile userProfile = synapse.getUserProfile(sub.getUserId());
				submittingUserOrTeamName = MessageUtils.getDisplayNameWithUserName(userProfile);
				idToNameCache.put(submittingUserOrTeamId, submittingUserOrTeamName);
			}
		} else {
			submittingUserOrTeamId = sub.getTeamId();
			submittingUserOrTeamName=idToNameCache.get(submittingUserOrTeamId);
			if (submittingUserOrTeamName==null) {
				Team team = synapse.getTeam(sub.getTeamId());
				submittingUserOrTeamName = team.getName();
				idToNameCache.put(submittingUserOrTeamId, submittingUserOrTeamName);
			}
		}
		return new Submitter(submittingUserOrTeamId, submittingUserOrTeamName);
	}

	public static List<String> getSubmissionContributors(Submission submission) {
		List<String> result = new ArrayList<String>();
		for (SubmissionContributor contributor : submission.getContributors()) {
			result.add(contributor.getPrincipalId());
		}
		return result;
	}



}
