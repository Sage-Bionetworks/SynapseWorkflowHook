package org.sagebionetworks;

public class WESWorkflowStatus {
	boolean isRunning;
	int exitCode; // is only meaningful if 'isRunning' is false
	Double progress;

	public boolean isRunning() {
		return isRunning;
	}

	public void setRunning(boolean isRunning) {
		this.isRunning = isRunning;
	}

	public int getExitCode() {
		return exitCode;
	}

	public void setExitCode(int exitCode) {
		this.exitCode = exitCode;
	}

	public Double getProgress() {
		return progress;
	}

	public void setProgress(Double progress) {
		this.progress = progress;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + exitCode;
		result = prime * result + (isRunning ? 1231 : 1237);
		result = prime * result + ((progress == null) ? 0 : progress.hashCode());
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
		WESWorkflowStatus other = (WESWorkflowStatus) obj;
		if (exitCode != other.exitCode)
			return false;
		if (isRunning != other.isRunning)
			return false;
		if (progress == null) {
			if (other.progress != null)
				return false;
		} else if (!progress.equals(other.progress))
			return false;
		return true;
	}

}
