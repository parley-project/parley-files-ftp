# parley-files-ftp

A [parley-files](https://github.com/tony-bringardner/parley-files) `FileSource` implementation for
FTP and FTPS servers, part of **Parley**, a family of Java libraries for implementing internet
protocols. It uses the [parley-ftp](https://github.com/tony-bringardner/parley-ftp) client.

Put it on the class path and `FileSourceFactory` finds it through `ServiceLoader`
(`FtpFileSourceFactory` and `FtpsFileSourceFactory`); application code keeps working with
`FileSource` and doesn't need to name this module.

Requires Java 11 or later. Depends on `parley-files` and `parley-ftp`.

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-files-ftp</artifactId>
    <version>1.0.0</version>
</dependency>
```

> parley-files-ftp was previously `bringardner:bjl_file_system_ftp` (BjlFileSystemFtp), with packages
> under `us.bringardner.io.filesource.ftp` and `.ftps`. They are now `us.bringardner.parley.files.ftp`
> and `us.bringardner.parley.files.ftps`.

## Links

FTP has no command to create a link, so `createSymbolicLink` and `createLink` throw
`UnsupportedOperationException`. Links that already exist on the server are read when the server says
which entries are links: a `LIST` line `name -> target` or an `MLST`/`MLSD` type `OS.unix=slink:target`.
Many servers (including parley-ftp's) follow links silently and don't say, and for those a link simply
looks like the file or directory it leads to.

When a server does say:

- `getLinkedTo()` returns the target (a relative target is taken from the link's directory, with `.` and
  `..` resolved), and null for anything that isn't a link.
- A link answers for what it leads to in `exists()`, `isFile()`, `isDirectory()`, `length()` and
  `lastModified()`. One that leads nowhere is there but doesn't exist; a loop of links answers no.
- **What a write to a link does is the server's decision**, because FTP has no idea of a link (parley-ftp's
  server, for one, replaces the link with a regular file, so a client never changes what it leads to; see
  its [Links](https://github.com/tony-bringardner/parley-ftp#links)). Unlike the other file systems here,
  the `FileSource` can't tell you which.
- `getCanonicalPath()` is **lexical**: `.` and `..` are resolved and a link is not (FTP has no realpath).
  So a link and the file it leads to have different canonical paths here, and `isChildOfMine()` can't see
  that a link leads outside a directory. Ask `getLinkedTo()` for the target.

## Tests

The tests start an FTP server from parley-ftp on port 8021 (`-DFtpPort=...` to change it) and run
the shared FileSource test suite from parley-files against it.
