package org.sagebionetworks;

import org.sagebionetworks.repo.model.Folder;

public class SubmissionFolderAndLogTail {
	private Folder submissionFolder;
	private String logTail;
	
	public SubmissionFolderAndLogTail(Folder submissionFolder, String logTail) {
		super();
		this.submissionFolder = submissionFolder;
		this.logTail = logTail;
	}
	public Folder getSubmissionFolder() {
		return submissionFolder;
	}
	public void setSubmissionFolder(Folder submissionFolder) {
		this.submissionFolder = submissionFolder;
	}
	public String getLogTail() {
		return logTail;
	}
	public void setLogTail(String logTail) {
		this.logTail = logTail;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((logTail == null) ? 0 : logTail.hashCode());
		result = prime * result + ((submissionFolder == null) ? 0 : submissionFolder.hashCode());
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
		SubmissionFolderAndLogTail other = (SubmissionFolderAndLogTail) obj;
		if (logTail == null) {
			if (other.logTail != null)
				return false;
		} else if (!logTail.equals(other.logTail))
			return false;
		if (submissionFolder == null) {
			if (other.submissionFolder != null)
				return false;
		} else if (!submissionFolder.equals(other.submissionFolder))
			return false;
		return true;
	}

	

}
