package org.sagebionetworks;



public interface Executable<T> {
	T execute() throws Throwable;
}
