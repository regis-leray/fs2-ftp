# FS2 with SFTP - FTP / FTPS

fs2 ftp client built on top of [Cats Effect](https://typelevel.org/cats-effect/), [Fs2](http://fs2.io/) and the sftp java client [sshj](https://github.com/hierynomus/sshj) and ftp/ftps client [commons-net](https://commons.apache.org/proper/commons-net/) 

[![Build Status](https://github.com/regis-leray/fs2-ftp/actions/workflows/ci.yml/badge.svg)](https://github.com/regis-leray/fs2-ftp/actions?query=branch%3Amaster)
[![codecov](https://codecov.io/gh/regis-leray/fs2-ftp/branch/master/graph/badge.svg)](https://codecov.io/gh/regis-leray/fs2-ftp)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.regis-leray/fs2-ftp_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Cfs2-ftp) 
<a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>

## Setup

```
// Supports scala versions (2.12 / 2.13 / 3.1.x)

libraryDependencies += "com.github.regis-leray" %% "fs2-ftp" % "<version>"
```

## How to use it ?

### FTP / FTPS

```scala
import cats.effect.IO
import fs2.ftp.UnsecureFtp._
import fs2.ftp.FtpSettings._

// FTP
val settings = UnsecureFtpSettings("127.0.0.1", 21, FtpCredentials("foo", "bar"))
// FTP-SSL 
val settings = UnsecureFtpSettings.ssl("127.0.0.1", 21, FtpCredentials("foo", "bar"))

connect[IO](settings).use{
  _.ls("/").compile.toList
}
```

### SFTP

#### Password authentication
```scala
import fs2.ftp.SecureFtp._
import fs2.ftp.FtpSettings._
import cats.effect.IO

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

connect[IO](settings).use(
  _.ls("/").compile.toList
)     
 ```

#### private key authentication
```scala
import fs2.ftp.SecureFtp._
import fs2.ftp.FtpSettings._
import java.nio.file.Paths._
import cats.effect.IO

// Provide a SftpIdentity implementation

val keyFile = KeyFileSftpIdentity(Paths.get("privateKeyStringPath"))

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", ""), keyFile)

connect[IO](settings).use(
  _.ls("/").compile.toList
)     
 ```

## Required Runtime

Since all (s)ftp command are IO bound task , it needs to be executed on specific runtime.
More information about IO and Cats Effect can be found here https://typelevel.org/cats-effect/docs/tutorial


The following example show how to create a `FtpClient[F[_], +A]` by using `connect()`

* Here the runtime is provided by `IOApp.Simple`
```scala
import cats.effect.{IO, IOApp}
import fs2.ftp.FtpSettings._

object MyApp extends IOApp.Simple {
  //F[_] Effect will be set as cats.effect.IO

  private val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

  //print all files/directories
  def run: IO[Unit] = {
    connect[IO, SecureFtp.Client](settings).use {
      _.ls("/mypath")
        .evalTap(r => IO(println(r)))
        .compile
        .drain
    }
  }
}
```

## Support any commands ?
The underlying client is safely exposed and you have access to all possible ftp commands

```scala
import cats.effect.IO
import fs2.ftp.SecureFtp._
import fs2.ftp.FtpSettings._

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

connect[IO](settings).use(
  _.execute(_.version())
)     
 ```

## Support any effect (IO, Monix, ZIO)

Since the library support polymorphic in the effect type `F[_]` (as long as it is compatible with cats-effect typeclasses), fs2-ftp can be used with other effect libraries such as Monix / ZIO.

The library is by default bringing with `cats-effect` dependency as the default effect system implementation.

### exemple for monix

You will need to use add in build.sbt [monix-eval](https://github.com/monix/monix)

```
libraryDependencies += "io.monix" %% "monix-eval" % "<version>"
```

```scala
import fs2.ftp.FtpSettings._
import fs2.ftp._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import Task.contextShift

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

val _: monix.Task[List[FtpResource]] = connect(settings).use {
  _.ls("/").compile.toList
}
```

### exemple for zio

You will need to use add in build.sbt [zio-cats-interop](https://github.com/zio/interop-cats)

```
libraryDependencies += "dev.zio" %% "zio-interop-cats" % "<version>"
```

```scala
import fs2.ftp.FtpSettings._
import zio.interop.catz._
import zio.ZIO

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

ZIO.runtime.map { implicit r: zio.Runtime[Any] =>
  implicit val CE: ConcurrentEffect[zio.Task] = implicitly
  implicit val CS: ContextShift[zio.Task] = implicitly

  val _: zio.Task[List[FtpResource]] = connect(settings).use {
    _.ls("/").compile.toList
  }
}
```


## How to release

1. How to create a key to signed artifact

```
# generate key
$ gpg --gen-key

# list the keys
$ gpg --list-keys

/home/foo/.gnupg/pubring.gpg
------------------------------

pub   rsa4096 2018-08-22 [SC]
      1234517530FB96F147C6A146A326F592D39AAAAA
uid           [ultimate] your name <you@example.com>
sub   rsa4096 2018-08-22 [E]

#send key to server
$> gpg --keyserver keyserver.ubuntu.com --send-keys $LONG_ID

# declare in travis (settings) PGP_SECRET in base64 (with no return carriage), dont put "" around the value !
gpg --armor --export-secret-keys $LONG_ID | base64 -w0 | pbcopy

# declare in travis (settings) PGP_PASSPHRASE in plain text
The randomly generated password you used to create a fresh gpg key
```

2. create a tag and push

more information here => https://github.com/olafurpg/sbt-ci-release

## LICENSE

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with
the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
