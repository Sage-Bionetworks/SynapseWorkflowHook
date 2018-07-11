package org.sagebionetworks;

import java.io.File;

import com.github.dockerjava.api.model.Container;

public class WorkflowJobImpl implements WorkflowJob {
	private String submissionId;
	private String containerName;
	private Container container;
	private File submissionParameters;

	public WorkflowJobImpl() {}
	
	public String getSubmissionId() {
		return submissionId;
	}
	
	public void setSubmissionId(String submissionId) {
		this.submissionId = submissionId;
	}
	
	public String getWorkflowId() {
		return containerName;
	}
	
	public String getContainerName() {
		return containerName;
	}
	public void setContainerName(String containerName) {
		this.containerName = containerName;
	}
	public Container getContainer() {
		return container;
	}
	public void setContainer(Container container) {
		this.container = container;
	}
	public File getSubmissionParameters() {
		return submissionParameters;
	}
	public void setSubmissionParameters(File submissionParameters) {
		this.submissionParameters = submissionParameters;
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((container == null) ? 0 : container.hashCode());
		result = prime * result + ((containerName == null) ? 0 : containerName.hashCode());
		result = prime * result + ((submissionId == null) ? 0 : submissionId.hashCode());
		result = prime * result + ((submissionParameters == null) ? 0 : submissionParameters.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		WorkflowJobImpl other = (WorkflowJobImpl) obj;
		if (container == null) {
			if (other.container != null)
				return false;
		} else if (!container.equals(other.container))
			return false;
		if (containerName == null) {
			if (other.containerName != null)
				return false;
		} else if (!containerName.equals(other.containerName))
			return false;
		if (submissionId == null) {
			if (other.submissionId != null)
				return false;
		} else if (!submissionId.equals(other.submissionId))
			return false;
		if (submissionParameters == null) {
			if (other.submissionParameters != null)
				return false;
		} else if (!submissionParameters.equals(other.submissionParameters))
			return false;
		return true;
	}
	@Override
	public String toString() {
		return "WorkflowJobImpl [submissionId=" + submissionId + ", containerName=" + containerName + ", container="
				+ container + ", submissionParameters=" + submissionParameters + "]";
	}


}
