package org.sagebionetworks;

import java.net.URL;

public class WorkflowURLAndEntrypoint {
	private URL workflowUrl;
	private String entryPoint;

	public WorkflowURLAndEntrypoint(URL workflowUrl, String entryPoint) {
		super();
		this.workflowUrl = workflowUrl;
		this.entryPoint = entryPoint;
	}

	public URL getWorkflowUrl() {
		return workflowUrl;
	}

	public void setWorkflowUrl(URL workflowUrl) {
		this.workflowUrl = workflowUrl;
	}

	public String getEntryPoint() {
		return entryPoint;
	}

	public void setEntryPoint(String entryPoint) {
		this.entryPoint = entryPoint;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((entryPoint == null) ? 0 : entryPoint.hashCode());
		result = prime * result + ((workflowUrl == null) ? 0 : workflowUrl.hashCode());
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
		WorkflowURLAndEntrypoint other = (WorkflowURLAndEntrypoint) obj;
		if (entryPoint == null) {
			if (other.entryPoint != null)
				return false;
		} else if (!entryPoint.equals(other.entryPoint))
			return false;
		if (workflowUrl == null) {
			if (other.workflowUrl != null)
				return false;
		} else if (!workflowUrl.equals(other.workflowUrl))
			return false;
		return true;
	}

}
