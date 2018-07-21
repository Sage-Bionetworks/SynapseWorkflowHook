package org.sagebionetworks;

import java.io.File;

/*
 * This class contains a directory path which is relative to a 
 * Docker mounted directory.  To use it one needs to know the
 * root of the mounted directory and append this to give the full path.
 */
public class ContainerRelativeFile {
	private String relativePath;
	private File containerPath;
	private File hostPath;
	
	/**
	 * 
	 * @param relativePath path relative to the root
	 * @param containerRoot root path as seen by the host
	 * @param hostRoot root path as seen from within the container
	 */
	public ContainerRelativeFile(String relativePath, File containerRoot, File hostRoot) {
		this.relativePath=relativePath;
		containerPath = new File(containerRoot, relativePath);
		hostPath = new File(hostRoot, relativePath);
	}
	
	public File getContainerPath() {
		return containerPath;
	}
	
	public File getHostPath() {
		return hostPath;
	}
	
	/*
	 * Constructs a full path from any root, e.g. for use when root is mounted under a 
	 * different name in another container.
	 */
	public File getAltPath(File root) {
		return new File(root, relativePath);
	}

}
