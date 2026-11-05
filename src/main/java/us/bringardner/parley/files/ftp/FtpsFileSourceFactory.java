package us.bringardner.parley.files.ftp;

/**
 * FileSource factory for FTPS servers with implicit TLS: {@link FtpFileSourceFactory} with TLS from the
 * first byte, on port 990 unless another is given. For explicit TLS (AUTH TLS, usually on port 21) use
 * the ftp type with secure set. Locks, atomic create and FileChannel behave as for FTP.
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
	}

	@Override
	protected boolean defaultSecure() {
		return true;
	}

	/** Implicit TLS, on port 990 unless another is given. */
	@Override
	protected boolean implicitTls() {
		return true;
	}

	/** Always: an ftps connection is TLS from the start, whatever secure says. */
	@Override
	public boolean isSecure() {
		return true;
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
