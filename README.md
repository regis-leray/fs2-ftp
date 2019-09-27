# FS2 with SFTP - FTP / FTPS

thin wrapper over ftp and sftp client, which wrap with TypeSafe libraries

[![Build Status](https://travis-ci.org/regis-leray/fs2-ftp.svg?branch=master)](https://travis-ci.org/regis-leray/fs2-ftp)
[![codecov](https://codecov.io/gh/regis-leray/fs2-ftp/branch/master/graph/badge.svg)](https://codecov.io/gh/regis-leray/fs2-ftp)

Setup
-----

```
//support scala 2.11 / 2.12

libraryDependencies += "com.github.regis-leray" %% "fs2-ftp" % "0.1.0"
```

How to use it ?
---

* FTP / FTPS

```scala
import ray.fs2.ftp.Ftp._

// FTP
val settings = FtpSettings("127.0.0.1", 21, credentials("foo", "bar"))
// FTPS
val settings = FtpSettings.secure("127.0.0.1", 21, credentials("foo", "bar"))

(for {
        client <- connect[IO](settings)
        files <- listFiles[IO](client)("/").compile.toList
        _ <- disconnect[IO](client)
      } yield files)      
 ```

* SFTP

```scala
import ray.fs2.ftp.SFtp._
import net.schmizz.sshj.{DefaultConfig, SSHClient}

implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)
val settings = SFtpSettings("127.0.0.1", 22, credentials("foo", "bar"))

(for {
        client <- connect[IO](settings)
        files <- listFiles[IO](client)("/").compile.toList
        _ <- disconnect[IO](client)
      } yield files)      
 ```