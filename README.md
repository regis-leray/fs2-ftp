# FS2 with SFTP - FTP / FTPS

fs2 ftp client built on top of [Cats Effect](https://typelevel.org/cats-effect/), [Fs2](http://fs2.io/) and the sftp java client [sshj](https://github.com/hierynomus/sshj) and ftp/ftps client [commons-net](https://commons.apache.org/proper/commons-net/) 

[![Build Status](https://travis-ci.org/regis-leray/fs2-ftp.svg?branch=master)](https://travis-ci.org/regis-leray/fs2-ftp)
[![codecov](https://codecov.io/gh/regis-leray/fs2-ftp/branch/master/graph/badge.svg)](https://codecov.io/gh/regis-leray/fs2-ftp)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.regis-leray/fs2-ftp_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Cfs2-ftp) 
<a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>

Setup
-----

```
//support scala 2.11 /  2.12 / 2.13

libraryDependencies += "com.github.regis-leray" %% "fs2-ftp" % "0.5.0"
```

How to use it ?
---

* FTP / FTPS

```scala
import ray.fs2.ftp.FtpClient._
import ray.fs2.ftp.FtpSettings._

// FTP
val settings = UnsecureFtpSettings("127.0.0.1", 21, FtpCredentials("foo", "bar"))
// FTP-SSL 
val settings = UnsecureFtpSettings.secure("127.0.0.1", 21, FtpCredentials("foo", "bar"))

connect(settings).use{
  _.ls("/").compile.toList
}
```

* SFTP

```scala
import ray.fs2.ftp.FtpClient._
import ray.fs2.ftp.FtpSettings._

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

connect(settings).use(
  _.ls("/").compile.toList
)     
 ```

Required BlockingIO
---

```scala
trait FtpClient[+A] {
  def stat(path: String)(implicit ec: ExecutionContext): IO[Option[FtpResource]]

  def readFile(path: String, chunkSize: Int = 2048)(
    implicit ec: ExecutionContext,
    cs: ContextShift[IO]
  ): fs2.Stream[IO, Byte]
  def rm(path: String)(implicit ec: ExecutionContext): IO[Unit]
  def rmdir(path: String)(implicit ec: ExecutionContext): IO[Unit]
  def mkdir(path: String)(implicit ec: ExecutionContext): IO[Unit]
  def ls(path: String)(implicit ec: ExecutionContext): Stream[IO, FtpResource]
  def lsDescendant(path: String)(implicit ec: ExecutionContext): Stream[IO, FtpResource]
  def upload(path: String, source: fs2.Stream[IO, Byte])(implicit ec: ExecutionContext, cs: ContextShift[IO]): IO[Unit]
  def execute[T](f: A => T)(implicit ec: ExecutionContext): IO[T]
}
```

All function required an implicit Execution Context.

Since all (s)ftp command are IO bound task , it is required to provide an unbounded size Thread Pool (ExecutionContext)

More explanation: https://typelevel.org/cats-effect/concurrency/basics.html#choosing-thread-pool


Here how to create an unbounded Execution Context
```scala
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

implicit val blockingIO = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
```




Support any commands ?
---

The underlying client is safely exposed and you have access to all possible ftp commands

```scala
import ray.fs2.ftp.FtpClient._
import ray.fs2.ftp.FtpSettings._

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

connect(settings).use(
  _.execute(_.version())
)     
 ```
 




How to release
---

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
$> gpg --keyserver hkp://ipv4.pool.sks-keyservers.net --send-keys $LONG_ID

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
