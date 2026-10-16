# Changelog

## parley-files-ftp 1.0.0 (2026-10-16)

BjlFileSystemFtp (`bringardner:bjl_file_system_ftp` 1.0.0-SNAPSHOT) is now **parley-files-ftp**, part
of the Parley library family. The code is the same; only names changed.

### Changed (needs a code change)

- Maven coordinates: `bringardner:bjl_file_system_ftp` is now `us.bringardner.parley:parley-files-ftp`.
- Packages: `us.bringardner.io.filesource.ftp` and `.ftps` are now `us.bringardner.parley.files.ftp`
  and `us.bringardner.parley.files.ftps`.
- `ServiceLoader` registration: `META-INF/services/us.bringardner.parley.files.FileSourceFactory`.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.files.ftp`.
- Dependencies: `bjl_net_ftp` and `bjl_file_system` are now `parley-ftp` and `parley-files`.
- `FtpEditPropertiesPanel` is gone (it started with one particular account filled in): the factory
  describes its settings with `getConnectionSettings()` (see parley-files), and the module no longer
  uses Swing. `getConnectProperties()` now includes `timeout`, which `setConnectionProperties` reads.
- `listFiles(ProgressMonitor)` is now `listFiles(FileSourceProgress)`.

### Added

- **Streams run on connections of their own.** A control connection runs one transfer and refused every
  command while it did, so two open streams, or a listing while a stream was open, failed. Each stream now
  uses its own connection from a small pool (up to 4 finished ones are kept,
  `MAX_IDLE_TRANSFER_CONNECTIONS`, and closed on disconnect; the main connection is used if the server
  refuses another). Any number of streams can be
  open at once and commands work meanwhile.
- **A read-only seekable stream** (`getSeekableInputStream()`): acts as a `RandomAccessFile` opened with
  `r`, over `REST`. A seek costs nothing until the next read, which reads through a short step forward or
  starts a transfer at the new place. There is still no read-write random access: FTP can't write at an
  offset.
- `getLinkedTo()` returns where a link points when the server's listing says so (`LIST` `name -> target`,
  `MLST` `type=OS.unix=slink:target`); a link answers for what it points to in `exists`, `isFile`,
  `isDirectory`, `length` and `lastModified`, one that leads nowhere is there but doesn't exist, and a loop
  of links answers no. FTP has no command to make a link, so `createLink` and `createSymbolicLink` still
  throw `UnsupportedOperationException`.
- `bufferSize` (also the system property `ftp.bufferSize`; 64 KB, within 8 KB and 16 MB): how much a
  stream moves at a time, and a stream can be opened with its own (`StreamOption.BUFFER_SIZE`). It isn't a
  setting in connection dialogs.
- `attributeCacheTtl` / `setAttributeCacheTtl` (system property `ftp.attributeCacheTtl`; 2000 ms, 0 asks
  every time): how long a FileSource trusts what it found of a file and a listing. A change made through
  the same factory is seen at once, whatever this is.

### Changed (no code change needed)

- `getCanonicalPath()` is lexical and says so (FTP has no realpath): `.` and `..` are resolved and a link
  is not. A caller that needs the file a link leads to asks `getLinkedTo()`.
- In the way of `java.io.File`: `createNewFile` (an empty `STOR`), `isFile`, `delete` of a missing file,
  `listFiles(null)`, a filter on what isn't a directory (null), `getOwner`/`getGroup` of an unknown file,
  `canRead`/`canWrite`/`canExecute` only for what exists, `mkdirs` (false for what is there), `renameTo`
  (itself, onto something, a missing directory), `setReadOnly` (write bits only), the two argument
  permission setters, `equals`/`hashCode`/`compareTo`, and a written file's directory and entry are
  looked up again.
- `getInputStream(startingPos)` at or past the end is an empty stream (the server refused the `REST` with
  `554`).
- A failed login reports why, where it threw a `NullPointerException`; `createThreadSafeCopy()` of an FTPS
  factory is FTPS again.
