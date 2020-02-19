package ray.fs2.ftp

import java.io._

import cats.effect.{ Blocker, ContextShift, IO, Resource }
import cats.syntax.applicativeError._
import fs2.Stream
import fs2.Stream._
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.{ OpenMode, Response, SFTPException, SFTPClient => JSFTPClient }
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import ray.fs2.ftp.FtpSettings.{ KeyFileSftpIdentity, RawKeySftpIdentity, SecureFtpSettings, SftpIdentity }

import scala.jdk.CollectionConverters._

final private class SFtp(unsafeClient: JSFTPClient, blocker: Blocker) extends FtpClient[JSFTPClient] {

  def ls(path: String)(implicit cs: ContextShift[IO]): fs2.Stream[IO, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.ls(path).asScala.toSeq))
      .map(FtpResource(_))
      .recoverWith {
        case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE => fs2.Stream.empty.covary[IO]
        case other                                                                     => fs2.Stream.raiseError[IO](other)
      }

  def lsDescendant(path: String)(implicit cs: ContextShift[IO]): fs2.Stream[IO, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.ls(path).asScala.toSeq))
      .flatMap(f => if (f.isDirectory) lsDescendant(f.getPath) else Stream(FtpResource(f)))
      .recoverWith {
        case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE => fs2.Stream.empty.covary[IO]
        case other                                                                     => fs2.Stream.raiseError[IO](other)
      }

  def stat(path: String)(implicit cs: ContextShift[IO]): IO[Option[FtpResource]] =
    execute(client => Option(client.statExistence(path)).map(FtpResource(path, _)))

  def readFile(
    path: String,
    chunkSize: Int = 10 * 1024
  )(implicit cs: ContextShift[IO]): fs2.Stream[IO, Byte] =
    for {
      remoteFile <- Stream.eval(execute(_.open(path, java.util.EnumSet.of(OpenMode.READ))))

      is: java.io.InputStream = new remoteFile.ReadAheadRemoteFileInputStream(64) {

        override def close(): Unit =
          try {
            super.close()
          } finally {
            remoteFile.close()
          }
      }

      input <- fs2.io.readInputStream(IO.pure(is), chunkSize, blocker)
    } yield input

  def rm(path: String)(implicit cs: ContextShift[IO]): IO[Unit] =
    execute(_.rm(path))

  def rmdir(path: String)(implicit cs: ContextShift[IO]): IO[Unit] =
    execute(_.rmdir(path))

  def mkdir(path: String)(implicit cs: ContextShift[IO]): IO[Unit] =
    execute(_.mkdir(path))

  def upload(
    path: String,
    source: fs2.Stream[IO, Byte]
  )(implicit cs: ContextShift[IO]): IO[Unit] =
    (for {
      remoteFile <- Stream.eval(execute(_.open(path, java.util.EnumSet.of(OpenMode.WRITE, OpenMode.CREAT))))

      os: java.io.OutputStream = new remoteFile.RemoteFileOutputStream() {

        override def close(): Unit =
          try {
            remoteFile.close()
          } finally {
            super.close()
          }
      }
      _ <- source.through(fs2.io.writeOutputStream(IO.pure(os), blocker))
    } yield ()).compile.drain

  def execute[T](f: JSFTPClient => T)(implicit cs: ContextShift[IO]): IO[T] =
    blocker.delay[IO, T](f(unsafeClient))
}

object SFtp {

  def connect(settings: SecureFtpSettings)(implicit cs: ContextShift[IO]): Resource[IO, FtpClient[JSFTPClient]] = {
    val ssh = new SSHClient(settings.sshConfig)

    for {
      blocker <- Blocker[IO]
      r <- Resource.make[IO, FtpClient[JSFTPClient]](IO.delay {
            import settings._

            if (!strictHostKeyChecking)
              ssh.addHostKeyVerifier(new PromiscuousVerifier)
            else
              knownHosts.map(new File(_)).foreach(ssh.loadKnownHosts)

            ssh.connect(host, port)

            sftpIdentity
              .fold(ssh.authPassword(credentials.username, credentials.password))(
                setIdentity(_, credentials.username)(ssh)
              )

            new SFtp(ssh.newSFTPClient(), blocker)
          })(client =>
            client.execute(_.close()).attempt.flatMap(_ => if (ssh.isConnected) IO.delay(ssh.disconnect()) else IO.unit)
          )
    } yield r
  }

  private[this] def setIdentity(identity: SftpIdentity, username: String)(ssh: SSHClient): Unit = {
    def bats(array: Array[Byte]): String = new String(array, "UTF-8")

    val passphrase =
      identity.passphrase.map(pass => PasswordUtils.createOneOff(bats(pass.getBytes).toCharArray)).orNull

    val keyProvider = identity match {
      case id: RawKeySftpIdentity =>
        ssh.loadKeys(bats(id.privateKey.getBytes), id.publicKey.map(p => bats(p.getBytes)).orNull, passphrase)
      case id: KeyFileSftpIdentity =>
        ssh.loadKeys(id.privateKey.toString, passphrase)
    }
    ssh.authPublickey(username, keyProvider)
  }
}
