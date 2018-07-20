package org.sagebionetworks;

import java.io.File;

/*
 * This class contains a directory path which is relative to a 
 * Docker mounted directory.  To use it one needs to know the
 * root of the mounted directory and append this to give the full path.
 */
public class ContainerRelativeFile {
	private String relativePath;
	
	public ContainerRelativeFile(String relativePath) {
		this.relativePath=relativePath;
	}
	
	public File getFullPath(File root) {
		return new File(root, relativePath);
	}

}
