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

## Tests

The tests start an FTP server from parley-ftp on port 8021 (`-DFtpPort=...` to change it) and run
the shared FileSource test suite from parley-files against it.
