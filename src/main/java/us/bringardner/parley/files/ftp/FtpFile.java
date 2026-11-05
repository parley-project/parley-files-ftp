/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~V000.01.10-V000.01.09-V000.01.07-V000.01.06-V000.01.02-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 14, 2006
 *
 */
package us.bringardner.parley.files.ftp;

import java.io.IOException;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.ftp.FTP;
import us.bringardner.parley.ftp.client.ClientFtpResponse;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.client.FtpClientFile;
import us.bringardner.parley.ftp.client.ListEntry;
import us.bringardner.parley.ftp.server.commands.Site;

/**
 * A directory entry of an FTP server, on the connection of an {@link FtpFileSourceFactory}. Most
 * of it is {@link FtpClientFile}; this adds the Unix permission bits, which are read from a LIST
 * line when the entry didn't have them (MLSx permissions don't map onto them).
 */
public class FtpFile extends FtpClientFile {

	private final FtpFileSourceFactory factory;
	/** The listing this entry came from, or null (see {@link Listing}). */
	private volatile Listing listing;

	private static Source source(FtpFileSourceFactory factory) {
		return new Source() {
			@Override
			public FtpClient getFtpClient() throws IOException {
				return factory.getFtpClient();
			}

			@Override
			public BaseObject getLogSource() {
				return factory;
			}
		};
	}

	public FtpFile(String dirPath, String listEntry, FtpFileSourceFactory factory) throws IOException {
		super(dirPath, listEntry, source(factory));
		this.factory = factory;
	}

	/**
	 * Only used in getParetFile
	 *
	 * @param factory
	 */
	public FtpFile(FtpFileSourceFactory factory) {
		super(source(factory));
		this.factory = factory;
	}

	@Override
	protected FtpClientFile newPlaceholder() {
		return new FtpFile(factory);
	}

	@Override
	public FtpFile getParetFile() {
		return (FtpFile) super.getParetFile();
	}

	public enum Permissions {
		OwnerRead('r'),
		OwnerWrite('w'),
		OwnerExecute('x'),

		GroupRead('r'),
		GroupWrite('w'),
		GroupExecute('x'),

		OtherRead('r'),
		OtherWrite('w'),
		OtherExecute('x');

	    public final char label;

	    private Permissions(char label) {
	        this.label = label;
	    }
	}
	
	//012345678
	//rwxr-xr-x
	public boolean canOwnerRead() throws IOException {
		return getPermissions()[Permissions.OwnerRead.ordinal()] == 'r';
	}

	public boolean canOwnerWrite() throws IOException {
		return getPermissions()[Permissions.OwnerWrite.ordinal()] == 'w';
	}

	public boolean canOwnerExecute() throws IOException {
		return getPermissions()[Permissions.OwnerExecute.ordinal()] == 'x';		
	}

	//012 345 678
	//rwx r-x r-x
	public boolean canGroupRead() throws IOException {
		return getPermissions()[Permissions.GroupRead.ordinal()] == 'r';
	}

	public boolean canGroupWrite() throws IOException {
		return getPermissions()[Permissions.GroupWrite.ordinal()] == 'w';
	}

	public boolean canGroupExecute() throws IOException {
		return getPermissions()[Permissions.GroupExecute.ordinal()] == 'x';		
	}

	//012 345 678
	//rwx r-x r-x
	public boolean canOtherRead() throws IOException {
		return getPermissions()[Permissions.OtherRead.ordinal()] == 'r';
	}

	public boolean canOtherWrite() throws IOException {
		return getPermissions()[Permissions.OtherWrite.ordinal()] == 'w';
	}

	public boolean canOtherExecute() throws IOException {
		return getPermissions()[Permissions.OtherExecute.ordinal()] == 'x';		
	}


	/**
	 * The permissions, read from the server the first time if the entry didn't have them; all
	 * dashes if the server doesn't say.
	 */
	@Override
	public char[] getPermissions() throws IOException {
		if( permissions == null || permissions.length != 9) {
			Listing l = listing;
			if( l != null ) {
				// not under this entry's lock: the listing takes it to fill every entry
				l.read(client(), this::logError);
			} else {
				synchronized (this) {
					if( permissions == null || permissions.length != 9) {
						permissions = listPermissions();
					}
				}
			}
		}
		if( permissions == null ) {
			// default to no permissions
			return "---------".toCharArray();
		}
		return permissions;
	}

	/**
	 * The entries of one folder listing. MLSx facts have no Unix permission bits, so they come
	 * from a LIST line. The first entry asked for
	 * them lists the folder once and gives every entry of the listing its line: each entry used to
	 * list on its own (a file its own path, a directory its whole parent folder), so showing a
	 * folder of n entries with their permissions took n+1 listings.
	 */
	static final class Listing {
		private final String dir;
		private final java.util.List<FtpFile> files = new java.util.ArrayList<>();
		private boolean read;

		Listing(String dir) {
			this.dir = dir;
		}

		void add(FtpFile f) {
			files.add(f);
			f.listing = this;
		}

		/** Lists the folder (once) and gives each entry its permissions. */
		synchronized void read(FtpClient client, java.util.function.Consumer<String> problems) throws IOException {
			if( read ) {
				return;
			}
			read = true;
			String[] resp = client.executeList(true, dir);
			if( resp == null ) {
				return;
			}
			java.util.Map<String, ListEntry> byName = new java.util.HashMap<>();
			for(String line : resp) {
				ListEntry e = ListEntry.parse(line, false, problems);
				if( e.getName() != null ) {
					byName.put(e.getName(), e);
				}
			}
			for(FtpFile f : files) {
				ListEntry e = byName.get(f.name);
				if( e == null ) {
					continue;
				}
				synchronized (f) {
					if( f.permissions == null || f.permissions.length != 9 ) {
						f.permissions = e.getPermissions();
					}
				}
			}
		}
	}

	/**
	 * The permissions from a LIST line, null if there isn't one: from the listing this entry came
	 * from (one LIST for all its entries), else a file lists as its own line, and a directory's
	 * line is found in its parent's listing. (Directories used to get no permissions.)
	 */
	private char[] listPermissions() throws IOException {
		FtpClient client = client();
		Listing l = listing;
		if( l != null ) {
			l.read(client, this::logError);
			return permissions;
		}
		if( !isDirectory()) {
			String[] resp= client.executeList(true, getAbsolutePath());
			// should be one and only one line
			if( resp!=null && resp.length==1 && resp[0].length()>=10) {
				return resp[0].substring(1,10).toCharArray();
			}
			return null;
		}
		if( parent == null || parent.isEmpty() || name == null ) {
			return null;
		}
		String[] resp= client.executeList(true, parent);
		if( resp != null ) {
			for(String line : resp) {
				ListEntry e = ListEntry.parse(line, false, this::logError);
				if( name.equals(e.getName()) && e.getPermissions() != null ) {
					return e.getPermissions();
				}
			}
		}
		return null;
	}

	public int getUnixPermitionValue(char perms []) throws IOException {
		
		int user = ((perms[Permissions.OwnerRead.ordinal()]=='r') ? 4:0)
				| ((perms[Permissions.OwnerWrite.ordinal()]=='w') ? 2:0)
				| ((perms[Permissions.OwnerExecute.ordinal()]=='x') ? 1:0)
				;
		
		int group = ((perms[Permissions.GroupRead.ordinal()]=='r') ? 4:0)
				| ((perms[Permissions.GroupWrite.ordinal()]=='w') ? 2:0)
				| ((perms[Permissions.GroupExecute.ordinal()]=='x') ? 1:0)
				;
		int other = ((perms[Permissions.OtherRead.ordinal()]=='r') ? 4:0)
				| ((perms[Permissions.OtherWrite.ordinal()]=='w') ? 2:0)
				| ((perms[Permissions.OtherExecute.ordinal()]=='x') ? 1:0)
				;
		
		int ret = (user<<6) | (group<<3) | other;
		
		return ret;
	}
	
	public boolean setPermision(Permissions p, boolean b) throws IOException {
		int idx = p.ordinal();
		char perms [] = getPermissions();
		char label = b ? p.label:'-';
		boolean ret = perms[idx] == label;
		// nothing to do if it's already set
		if( !ret ) {
			perms[idx] = label;
			int val = getUnixPermitionValue(perms);
			String arg = Integer.toOctalString(val);
			String path = getAbsolutePath();
			ClientFtpResponse resp = client().executeCommand(FTP.SITE, Site.CMD_CHMOD,arg,path);
			ret = resp.isPositiveComplet();
			//  just to make sure client stays in sync with server;
			permissions = null;
			listing = null;
			factory.noteChange(path);
		}
		
		return ret;
	}





	// what changes the file tells the factory, so every FileSource of it forgets what it knew

	@Override
	public boolean delete() throws IOException {
		try {
			return super.delete();
		} finally {
			factory.noteChange(getAbsolutePath());
		}
	}

	@Override
	public boolean mkdir() throws IOException {
		try {
			return super.mkdir();
		} finally {
			factory.noteChange(getAbsolutePath());
		}
	}

	@Override
	public boolean mkdirs() throws IOException {
		try {
			return super.mkdirs();
		} finally {
			// any folder on the way may be new
			java.util.List<String> made = new java.util.ArrayList<>();
			for(String p = FtpFileSource.normalize(getAbsolutePath()); !p.equals(FtpClient.SEPARATOR); p = p.substring(0, Math.max(1, p.lastIndexOf(FtpClient.SEPARATOR_CHAR)))) {
				made.add(p);
			}
			factory.noteChange(made.toArray(new String[0]));
		}
	}

	@Override
	public boolean renameTo(String newAbsolutePath) throws IOException {
		String from = getAbsolutePath();
		try {
			return super.renameTo(newAbsolutePath);
		} finally {
			factory.noteChange(from, newAbsolutePath);
		}
	}

	/** MFCT, else MFF Create= (draft-somers-ftp-mfxx); false if the server has neither or refuses */
	public boolean setCreationTime(long time) {
		try {
			return client().setCreationTime(getAbsolutePath(), time);
		} catch (IOException e) {
			logError("Can't set the creation time of "+getAbsolutePath(), e);
			return false;
		} finally {
			factory.noteChange(getAbsolutePath());
		}
	}

	@Override
	public boolean setLastModified(long lastModifiedTime) {
		try {
			return super.setLastModified(lastModifiedTime);
		} finally {
			factory.noteChange(getAbsolutePath());
		}
	}
}
