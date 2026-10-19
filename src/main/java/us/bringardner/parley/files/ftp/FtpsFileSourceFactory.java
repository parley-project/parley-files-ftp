package us.bringardner.parley.files.ftp;

/**
 * FileSource factory for FTPS servers: {@link FtpFileSourceFactory} over TLS. Locks, atomic create and FileChannel behave as for FTP.
 * <p>
 * <h2>Locks, atomic create and FileChannel</h2>
 * <ul>
 * <li><b>Locks:</b> {@code PROCESS} scope: this JVM only (FTP has no file locks).</li>
 * <li><b>Atomic create:</b> <b>{@code createNewFile()} is not atomic</b>; {@code mkdir()} is.</li>
 * <li><b>FileChannel, map(), force():</b> as {@link FtpFileSourceFactory}: no random access, {@code READ_ONLY} maps a copy, {@code force()} only sends the data.</li>
 * </ul>
 * See parley-files' README ("Locking") for the rules every file system keeps.
 */
public class FtpsFileSourceFactory extends FtpFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public FtpsFileSourceFactory() {
		super();
		setSecure(true);
	}
	
	@Override
	protected FtpFileSourceFactory newCopy() {
		return new FtpsFileSourceFactory();
	}

	@Override
	public String getTitle() {
		return super.getTitle()+" Secure";
	}
	
	@Override
	public String getTypeId() {
		return "ftps";
	}
	
}
