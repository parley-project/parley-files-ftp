# Changelog

## parley-files-ftp 2.0.0 (unreleased)

### Changed (2026-11-07: parley-ftp's anonymous FTP is read only)

- **The tests' FTP servers give anonymous `read` and `write`**: parley-ftp's `AnonymousUserRegistry()` now gives
  anonymous `read` only, so every test server sets
  `setUserRegistry(new AnonymousUserRegistry(StandardPermissions.READ, StandardPermissions.WRITE))`. No change to the
  FileSource itself.

### Changed (2026-11-06: tests on parley-ftp's new parley-iam login)

- **The tests' FTP servers use parley-ftp's `AnonymousUserRegistry`**: parley-ftp 2.0.0 (on parley-iam and the new
  parley-net) has no built-in anonymous user, and a server with no user registry refuses every login. Every test
  server now sets `setUserRegistry(new AnonymousUserRegistry())`, which lets `anonymous`/`ftp` in with any password as
  before. No change to the FileSource itself: it logs in with a user and password as it did.

### Changed (needs a configuration change)

- **`secure` defaults to the factory's type** (To-Do 7.6): false for `ftp`, true for `ftps`, both for a new factory and
  for connection properties that leave `secure` out (1.x took a missing `secure` as true even for `ftp`, while a new
  `ftp` factory was false). A plain `ftp` connection that relied on the missing setting meaning FTPS now needs
  `secure=true`, or the `ftps` type.
- **A connection's settings come only from the connection** (To-Do 2.19, 7.5): 1.x's JVM-wide defaults `ftp.host`,
  `ftp.port`, `ftp.user` and `ftp.password` are gone, with no `parley.files.ftp.*` replacement (a password in a system
  property shows in process listings). The JVM-wide tuning defaults are `parley.files.ftp.bufferSize` and
  `parley.files.ftp.attributeCacheTtl`, now a duration (`2s`) (were `ftp.bufferSize`, `ftp.attributeCacheTtl`).
- **The `timeout` connection setting is a duration** (`4s`, `500ms`; a bare number is ms), and saved as one.
- **`ftp` with `secure` is explicit TLS; `ftps` is implicit TLS on port 990.** An `ftp` connection with `secure=true`
  connects in plain text and sends `AUTH TLS` before it logs in, and doesn't log in if the server refuses (the
  password is never sent in plain text); it stays on port 21. An `ftps` connection is TLS from the first byte, always
  (`secure` is ignored and saved as `true`, and the connection form has no TLS box for it), and its default port is
  990 (`FtpFileSourceFactory.DEFAULT_IMPLICIT_TLS_PORT`, `defaultPort()`). Both types used to take `secure=true` as
  implicit TLS on port 21 unless a port was given, which a server that starts in plain text (most do) answers with
  "Unsupported or unrecognized SSL message", and the client never sent `AUTH TLS` (`setRequestSecure(false)`, a TODO).
  A saved `ftp` connection with `secure=true` that went to an implicit TLS port (990) needs the `ftps` type now; a saved
  `ftps` connection with port 21 keeps 21. A file's URL names its own type (an `ftps` file's said `ftp`).

### Faster

- **One `LIST` per folder for permissions**: MLSx facts have no Unix permission bits, so each entry read them with a
  `LIST` of its own (a directory listed its whole parent folder), and showing a folder of n entries with
  `canRead()` took n+1 listings. The first entry asked now lists the folder once for every entry of its listing.
- **A change only drops what it touched**: a write, mkdir, delete, rename or `refresh()` drops what is known of that
  path, anything under it and its folder's listing. Any change used to drop what every FileSource of the factory knew.
  `refresh()` now reads the folder's listing again too (it relied on that).
- **Listings run on connections of their own**, kept for the next one as streams' are, so they no longer wait for each
  other or for the main connection.
- **Recent listings are shared**: a FileSource made from a path (a typed path, a recent file, a parent) takes its
  details and listing from a listing read within the attribute cache time, instead of `MLST` and another listing.

## parley-files-ftp 1.1.0 (unreleased)

Needs parley-files 1.1.0.

Needs parley-ftp 1.1.0 (`FtpClient.getOutputStream(path, ascii, startingPos, bufferSize)`) and parley-files 1.1.0
(`IRandomAccessIoController.checksAccessOnOpen()`).

### Added

- Times: `setLastModifiedTime()` sets the time (it was "not supported"): `MFMT` when the server lists it in `FEAT`,
  else parley's `SITE` command, as `setLastModified()` did. `setCreateTime()` uses `MFCT`/`MFF Create=`. Needs
  parley-ftp 1.1.0's `FtpClient.setCreationTime()`. See the README, "Times".

- **Read-write random access** (`getRandomAccessStream(mode)`, `FtpRandomAccessIoController`): chunks are read with
  `REST` and `RETR`; one in the middle of a file is written with `REST` and `STOR`, only when the new connection
  property `storeAtOffset` (`setStoreAtOffset(true)`, off by default) says the server stores in place there (servers
  differ: some refuse, some replace the file, some add to it). After every write the file's size is checked, so a
  server that didn't write in place is reported. A chunk at the end is added with `APPE`; `setLength()` adds zeros
  with `APPE`, and shortens a file by storing again the part that stays. FileChannels for reading and writing (and `map()` in `PRIVATE`/`READ_WRITE`) work through it. See the
  README, "Random access".
- Random access uses `RANG` (draft-bryan-ftp-range) when the server lists it in `FEAT`: a chunk is read with `RANG`
  then `RETR` (just the chunk is sent) and written anywhere, chunk 0 included, with `RANG` then `STOR`, in place and
  without `storeAtOffset`. `FtpFileSourceFactory.isRangSupported()`. Needs parley-ftp 1.1.0's `RANG` client methods.
- `supportedStreamOptions()` includes `CHUNK_SIZE` (the random access chunk size).
- File locks (`lock()`, `tryLock()`, see parley-files). FTP has no locks of its own, so they reach this
  JVM only (`getLockScope()` is `PROCESS`). `FtpLockTest` runs parley-files' lock contract over FTP.

### Changed (no code change needed)

- `setLastAccessTime()` is false: FTP can't set an access time. It used to set the modification time.

- `createNewFile()` appends zero bytes (APPE) instead of storing them (STOR), so a file another client
  created between the check and the store is no longer emptied. It still isn't atomic, which FTP can't
  do; FileSource's contract now says it must be, and the README says why FTP is the exception.

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
  starts a transfer at the new place. (1.0.0 had no read-write random access.)
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
