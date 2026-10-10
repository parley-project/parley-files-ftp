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
 * ~version~V000.01.07-V000.01.01-V000.01.00-V000.00.01-V000.00.00-
 */
/*
 * Created on Nov 24, 2006
 *
 */
package us.bringardner.parley.files.ftp;

import java.io.IOException;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Properties;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceUri;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.ftp.client.ClientFtpResponse;
import us.bringardner.parley.ftp.client.FtpClient;


public class FtpFileSourceFactory extends FileSourceFactory {

	/**
	 * FtpClientWrapper just routes logging to the factory
	 */
	private static class FtpClientWrapper extends FtpClient {
		FtpFileSourceFactory factory;


		public FtpClientWrapper(FtpFileSourceFactory ftpFileSourceFactory, String host, int port) {
			super(host, port);
			factory = ftpFileSourceFactory;
		}

		@Override
		public void logDebug(String msg) {
			factory.logDebug(msg);
		}

		@Override
		public void logDebug(String msg, Throwable error) {
			factory.logDebug(msg, error);
		}

		@Override
		public void logError(String msg) {
			factory.logError(msg);
		}

		@Override
		public void logError(String msg, Throwable error) {
			factory.logError(msg, error);
		}

		@Override
		public void logInfo(String msg) {
			factory.logInfo(msg);
		}

		@Override
		public void logInfo(String msg, Throwable error) {
			factory.logInfo(msg, error);
		}

		@Override
		public boolean isDebugEnabled() {
			return factory.isDebugEnabled();
		}

		@Override
		public boolean isErrorEnabled() {
			return factory.isErrorEnabled();
		}
		@Override
		public boolean isInfoEnabled() {
			return factory.isInfoEnabled();
		}

	}

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public static final String FACTORY_ID = "ftp";
	public static final String PROP_HOST = "host";
	public static final String PROP_PORT = "port";
	public static final String PROP_USER = "user";
	public static final String PROP_PSWD = "password";
	public static final String PROP_ACCT = "account";
	public static final int DEFAULT_PORT = 21;
	public static final String PROP_SECURE = "secure";
	public static final String PROP_TIMEOUT = "timeout";
	/**
	 * How much to move at a time (bytes), see {@link #setBufferSize(int)}.
	 * <p>
	 * This is for programmers: it isn't in {@link #getConnectionSettings()}, so no dialog
	 * shows it (the forms carry properties they don't describe through unchanged), and
	 * {@link #getConnectProperties()} only has it when it was set, so saved connections
	 * keep following the default. The JVM-wide default is the system property
	 * "ftp.bufferSize", like "ftp.host" and "ftp.port".
	 */
	public static final String PROP_BUFFER_SIZE = "bufferSize";
	/** What the FTP client has always used. */
	public static final int DEFAULT_BUFFER_SIZE = 1024*65;
	/** The client doesn't use a smaller buffer than this. */
	public static final int MIN_BUFFER_SIZE = 8*1024;
	public static final int MAX_BUFFER_SIZE = 16*1024*1024;



	private static boolean versionSupported = false;


	private  FtpFileSource currentDirectory; 
	private  FtpClient client;
	private  String host;
	private  int port = -1;
	private  String user;
	private  String passwd;
	private String account;
	private int timeout = 4000;
	/** null: the JVM default, else DEFAULT_BUFFER_SIZE. Only a value that was set is saved. */
	private volatile Integer bufferSize;
	private boolean secure = false;
	private FileSource[] roots;

	public FtpFileSourceFactory () {
		super();
	}



	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}



	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	public boolean isSecure() {
		return secure;
	}

	/** A relative path is taken from the server's current directory (PWD). */
	public FileSource createFileSource(String name) throws IOException {
		String path = name == null ? "" : name.replace('\\', '/');
		if( !path.startsWith("/")) {
			return getCurrentDirectory().getChild(path);
		}
		return new FtpFileSource(path,this);
	}

	public FileSource createFileSource(String parent, String name) throws IOException {
		return createFileSource((parent == null ? "" : parent)+"/"+name);
	}

	/**
	 * The same server account: same host (ignoring case), port and user. Paths from
	 * such factories name the same files, so isChildOfMine can compare them.
	 */
	@Override
	public boolean isSameFileSystem(FileSourceFactory other) {
		if( other == this ) {
			return true;
		}
		if( !(other instanceof FtpFileSourceFactory)) {
			return false;
		}
		FtpFileSourceFactory o = (FtpFileSourceFactory) other;
		String h1 = getHost(), h2 = o.getHost();
		return getPort() == o.getPort()
				&& (h1 == null ? h2 == null : h1.equalsIgnoreCase(h2))
				&& java.util.Objects.equals(getUser(), o.getUser());
	}

	public String getAccount() {
		return account;
	}

	public void setAccount(String account) {
		this.account = account;
	}

	protected FtpFile [] listFiles(String dirPath) throws IOException {
		FtpFile [] ret = null;
		dirPath = dirPath.replaceAll("//", "/");
		String [] list = getFtpClient().executeList(dirPath);
		if( list == null ) {
			logDebug("null list returned from executeList on "+dirPath);   
		} else {
			ret = new FtpFile[list.length];
			for (int idx = 0; idx < list.length; idx++) {
				ret[idx] = new FtpFile(dirPath, list[idx], this);
			}

			/*
			 * I did a list and got one entry.  Try to determine
			 * if the name passed was a file name or a dir name.
			 * Seems like there should be something built into FTP 
			 * to handle some of these issues.
			 */
			if(list.length == 1  && ret[0].isFile() ) {
				int idx = dirPath.lastIndexOf(FtpClient.SEPERATOR_CHAR);
				if(idx >= 0 ) {
					String fileName = dirPath.substring(idx+1);
					if(fileName.equals(ret[0].getName())) {
						//  We probably sent a file name so the parent needs adjusted
						dirPath = dirPath.substring(0,idx);
						ret[0] = new FtpFile(dirPath,list[0],this);
					}

				}

			} 

		}

		return ret;
	}

	public String getTypeId() {

		return FACTORY_ID;
	}

	public boolean isVersionSupported() {
		return versionSupported;
	}

	public FileSource[] listRoots() {
		if( roots == null ) {
			roots = new FileSource[1];
			roots[0] = new FtpFileSource("/", this);
		}
		return roots;
	}

	public void setCurrentDirectory(FileSource dir) {
		this.currentDirectory=(FtpFileSource)dir;
	}


	public FtpFileSource  getCurrentDirectory() throws IOException {
		if( this.currentDirectory== null ) {
			try {
				FtpClient client = getFtpClient();
				this.currentDirectory = new FtpFileSource(client.executePwd(),this);
			} catch (SocketException ex) {
				throw new IOException(ex.toString());
			} 

		}
		return this.currentDirectory;
	}

	public  FtpClient getFtpClient() throws SocketException, IOException {
		if( client == null ) {
			synchronized (this) {
				if( client == null ) {

					FtpClientWrapper tmp = new FtpClientWrapper(this,getHost(),getPort());
					tmp.setSecure(secure);
					Integer buffer = configuredBufferSize();
					if( buffer != null ) {
						tmp.setTransferBufferSize(buffer);
					}
					// TODO:  Configure setRequestSecure
					tmp.setRequestSecure(false);

					if( !tmp.connect(getUser(),getPasswd(),getAccount())) {
						ClientFtpResponse res = client.getLastResponse();                
						String reply = res == null ? "No reply availible": res.getResponseText();
						String msg = "Can't log on to "+getHost()+":"+getPort()+" user= "+getUser()+" reply="+reply;
						logError(msg);
						throw new IllegalArgumentException(msg);
					}
					client = tmp;
				}
			}
		}

		return client;
	}


	private static int clampBufferSize(long size) {
		return (int) Math.max(MIN_BUFFER_SIZE, Math.min(MAX_BUFFER_SIZE, size));
	}

	/** The size that was set, else the system property ftp.bufferSize, else null (the client's default). */
	private Integer configuredBufferSize() {
		Integer ret = bufferSize;
		if( ret == null ) {
			String v = System.getProperty(FACTORY_ID+"."+PROP_BUFFER_SIZE);
			if( v != null && !v.trim().isEmpty()) {
				try {
					ret = clampBufferSize(Long.parseLong(v.trim()));
				} catch (NumberFormatException e) {
					// a bad value uses the default
				}
			}
		}
		return ret;
	}

	/**
	 * The size of the buffers that carry transfer data (and what a copy loop should
	 * read and write at a time): the one set with {@link #setBufferSize(int)} or
	 * {@link #PROP_BUFFER_SIZE}, else the system property ftp.bufferSize, else
	 * {@link #DEFAULT_BUFFER_SIZE}. A connection that is already open reports its own.
	 */
	public int getBufferSize() {
		FtpClient c = client;
		if( c != null ) {
			return c.getTransferBufferSize();
		}
		Integer ret = configuredBufferSize();
		return ret == null ? DEFAULT_BUFFER_SIZE : ret;
	}

	/**
	 * @param size bytes, kept within {@link #MIN_BUFFER_SIZE} and {@link #MAX_BUFFER_SIZE};
	 * takes effect for transfers started after this, also on a connection that is open
	 */
	public void setBufferSize(int size) {
		int value = clampBufferSize(size);
		bufferSize = value;
		FtpClient c = client;
		if( c != null ) {
			c.setTransferBufferSize(value);
		}
	}

	public  String getHost() {
		if( host == null ) {
			synchronized (this) {
				if( host == null ) {
					host = System.getProperty(FACTORY_ID+"."+PROP_HOST);
				}
			}            
		}

		return host;
	}

	public  void setHost(String host)  {
		this.host = host;
	}

	public  String getPasswd() {
		if( passwd == null ) {
			synchronized (this) {
				if( passwd == null ) {
					passwd = System.getProperty(FACTORY_ID+"."+PROP_PSWD);		
				}
			}            
		}

		return passwd;
	}

	public  void setPasswd(String passwd) {
		this.passwd = passwd;
	}

	public  int getPort() {
		if( port == -1 ) {
			synchronized (this) {
				if( port == -1 ) {
					String tmp = System.getProperty(FACTORY_ID+"."+PROP_PORT);
					if( tmp != null ) {
						try {
							port = Integer.parseInt(tmp);
						} catch(Exception ex) {}
					} else {
						port = DEFAULT_PORT;
					}                	
				}
			}
		}

		return port;
	}

	public  void setPort(int port) {
		if( port <= 0 ) {
			port = DEFAULT_PORT;
		}

		this.port = port;

	}

	public  String getUser() {
		if( user == null ) {
			synchronized (this) {
				if( user == null ) {
					user = System.getProperty(FACTORY_ID+"."+PROP_USER);		
				}
			}            
		}

		return user;
	}

	public  void setUser(String user) {
		if( user == null ) {    		
			user = null;
		} else {
			String [] tmp = user.split(":");
			this.user = tmp[0];
			if( tmp.length>1) {
				setPasswd(tmp[1]);
			}
		}        
	}

	@Override
	protected boolean connectImpl() {
		return isConnected();
	}

	@Override
	protected void disConnectImpl() {
		if( client != null ) {
			client.close();
			client = null;
		}
	}

	/**
	 * The password, and the account (RFC 959 ACCT), which servers that use it
	 * treat as a further credential.
	 */
	@Override
	public boolean isSecretProperty(String name) {
		return PROP_PSWD.equals(name) || PROP_ACCT.equals(name);
	}

	public Properties getConnectProperties() {
		Properties ret = new Properties();

		ret.setProperty(PROP_PORT, ""+getPort());        
		ret.setProperty(PROP_HOST, host==null?"":host);
		ret.setProperty(PROP_USER, user==null?"":user);        
		ret.setProperty(PROP_PSWD, passwd==null?"":passwd);        
		ret.setProperty(PROP_ACCT, account==null?"":account);
		ret.setProperty(PROP_SECURE,""+secure);
		ret.setProperty(PROP_TIMEOUT,""+timeout);
		Integer buffer = bufferSize;
		if( buffer != null ) {
			ret.setProperty(PROP_BUFFER_SIZE, ""+buffer);
		}

		return ret;
	}

	public boolean isConnected() {

		boolean ret = false;
		try {
			FtpClient client= getFtpClient();
			client.setCmdTimeout(timeout);
			client.setTxferTimeout(timeout);
			ret = client.isConnected();            
		} catch (Exception ex) {
			logError("",ex);
		}

		return ret;
	}

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(
				ConnectionSetting.text(PROP_HOST, "Host").asRequired(),
				ConnectionSetting.integer(PROP_PORT, "Port", 1L, 65535L).withDefault(""+DEFAULT_PORT).asRequired(),
				ConnectionSetting.text(PROP_USER, "User"),
				ConnectionSetting.secret(PROP_PSWD, "Password"),
				ConnectionSetting.bool(PROP_SECURE, "Use TLS (FTPS)"),
				ConnectionSetting.secret(PROP_ACCT, "Account").asAdvanced()
					.withDescription("RFC 959 ACCT; only some servers use it"),
				ConnectionSetting.integer(PROP_TIMEOUT, "Timeout (ms)", 0L, null).asAdvanced().withDefault("4000"));
	}



	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSourceFactory#setProperties(java.net.URL)
	 */

	public void setConnectionProperties(URL url) {
		if( url == null ) {
			setHost(null);
			setPort(DEFAULT_PORT);
			setUser(null);
			setPasswd(null);
			setAccount(null);
			secure = false;
		} else {
			try {
				FileSourceUri fsurl = new FileSourceUri(url.toURI());				
				setHost(fsurl.getHost());
				setPort(fsurl.getPort());
				setUser( fsurl.getUser());
				// TODO: What should this be
				setAccount(fsurl.getHost());
				setPasswd(fsurl.getPassword());
			} catch (IOException | URISyntaxException e) {
				// Not implemented
				e.printStackTrace();
			}

		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSourceFactory#setConnectionProperties(java.util.Properties)
	 */

	public void setConnectionProperties(Properties prop) {
		if( prop == null ) {
			setHost(null);
			setUser(null);
			setPasswd(null);
			setAccount(null);
			setPort(DEFAULT_PORT);
			secure = false;
			bufferSize = null;
		} else {
			setHost(prop.getProperty(PROP_HOST));
			int tmp = DEFAULT_PORT;
			try {
				tmp = Integer.parseInt(prop.getProperty(PROP_PORT,""+DEFAULT_PORT));
			} catch (Exception e) {
			}
			setPort(tmp);
			setUser(prop.getProperty(PROP_USER));
			setPasswd(prop.getProperty(PROP_PSWD));
			setAccount(prop.getProperty(PROP_ACCT));
			secure = prop.getProperty(PROP_SECURE, "true").equals("true");
			timeout = Integer.parseInt(prop.getProperty(PROP_TIMEOUT, ""+timeout));
			String buffer = prop.getProperty(PROP_BUFFER_SIZE);
			if( buffer != null && !buffer.trim().isEmpty()) {
				// No dialog shows this, so a bad value can't be fixed there and must not stop
				// the connection: say so and keep the size as it is.
				try {
					setBufferSize(clampBufferSize(Long.parseLong(buffer.trim())));
				} catch (NumberFormatException e) {
					logError("Ignoring "+PROP_BUFFER_SIZE+" '"+buffer+"': not a number");
				}
			}
		}
	}

	@Override
	/**
	 * return an FileSourceFactory with the same configuration but NOT the same connection.
	 */
	public FileSourceFactory createThreadSafeCopy() {
		FtpFileSourceFactory ret = newCopy();
		ret.host = host;
		ret.port = port;
		ret.passwd = passwd;
		ret.user = user;
		ret.account = account;
		ret.secure = secure;
		ret.timeout = timeout;
		ret.bufferSize = bufferSize;
		return ret;
	}

	/**
	 * A new, unconfigured factory of this class for createThreadSafeCopy(); a subclass
	 * overrides it so its copies are the same kind (an FTPS factory's copy must stay FTPS).
	 */
	protected FtpFileSourceFactory newCopy() {
		return new FtpFileSourceFactory();
	}

	@Override
	public String getTitle() {
		return FACTORY_ID+"://"+getUser()+"@"+getHost()+":"+getPort();
	}

	@Override
	public String getURL() {
		return FACTORY_ID+"://"+getUser()+":"+getPasswd()+"@"+getHost()+":"+getPort();
	}

	@Override
	public char getPathSeperatorChar() {
		return ':';
	}

	@Override
	public char getSeperatorChar() {
		return '/';
	}


	FileSourceUser principle = new FileSourceUser() {

		public String getName() {
			if( isConnected()) {
				return FtpFileSourceFactory.this.user;
			} else {
				return "UnKnown";
			}
		}		
	};

	@Override
	public FileSourceUser whoAmI() {
		return principle;
	}



	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new UnsupportedOperationException();
	}



	@Override
	public FileSource createLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new UnsupportedOperationException();
	}
}

