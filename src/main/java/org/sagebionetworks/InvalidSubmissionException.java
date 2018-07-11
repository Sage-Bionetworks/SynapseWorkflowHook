package org.sagebionetworks;

/*
 * This exception is thrown if the content of a submitted file is invalid
 */
public class InvalidSubmissionException extends Exception {
	private static final long serialVersionUID = 1L;

	public InvalidSubmissionException() {}

	public InvalidSubmissionException(String message) {
		super(message);
	}

	public InvalidSubmissionException(Throwable cause) {
		super(cause);
	}

	public InvalidSubmissionException(String message, Throwable cause) {
		super(message, cause);
	}

	public InvalidSubmissionException(String message, Throwable cause,
			boolean enableSuppression, boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

}
