package ray.fs2.ftp

import java.io._

import cats.effect.{ Blocker, ConcurrentEffect, ContextShift, Resource }
import cats.implicits._
import fs2.Stream
import fs2.Stream._
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.{ OpenMode, Response, SFTPException, SFTPClient => JSFTPClient }
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import ray.fs2.ftp.FtpSettings.{ KeyFileSftpIdentity, RawKeySftpIdentity, SecureFtpSettings, SftpIdentity }

import scala.jdk.CollectionConverters._

final private class SFtp[F[_]](unsafeClient: JSFTPClient, blocker: Blocker)(
  implicit CE: ConcurrentEffect[F],
  CS: ContextShift[F]
) extends FtpClient[F, JSFTPClient] {

  def ls(path: String): fs2.Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.ls(path).asScala.toSeq))
      .map(FtpResource(_))
      .recoverWith {
        case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE => fs2.Stream.empty.covary[F]
        case other                                                                     => fs2.Stream.raiseError[F](other)
      }

  def lsDescendant(path: String): fs2.Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.ls(path).asScala.toSeq))
      .flatMap(f => if (f.isDirectory) lsDescendant(f.getPath) else Stream(FtpResource(f)))
      .recoverWith {
        case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE => fs2.Stream.empty.covary[F]
        case other                                                                     => fs2.Stream.raiseError[F](other)
      }

  def stat(path: String): F[Option[FtpResource]] =
    execute(client => Option(client.statExistence(path)).map(FtpResource(path, _)))

  def readFile(
    path: String,
    chunkSize: Int = 10 * 1024
  ): fs2.Stream[F, Byte] =
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

      input <- fs2.io.readInputStream(CE.pure(is), chunkSize, blocker)
    } yield input

  def rm(path: String): F[Unit] =
    execute(_.rm(path))

  def rmdir(path: String): F[Unit] =
    execute(_.rmdir(path))

  def mkdir(path: String): F[Unit] =
    execute(_.mkdir(path))

  def upload(
    path: String,
    source: fs2.Stream[F, Byte]
  ): F[Unit] =
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
      _ <- source.through(fs2.io.writeOutputStream(CE.pure(os), blocker))
    } yield ()).compile.drain

  def execute[T](f: JSFTPClient => T): F[T] =
    blocker.delay[F, T](f(unsafeClient))
}

object SFtp {

  def connect[F[_]](
    settings: SecureFtpSettings
  )(implicit CS: ContextShift[F], CE: ConcurrentEffect[F]): Resource[F, FtpClient[F, JSFTPClient]] =
    for {
      ssh <- Resource.liftF(CE.delay(new SSHClient(settings.sshConfig)))

      blocker <- Blocker[F]
      r <- Resource.make[F, FtpClient[F, JSFTPClient]](CE.delay {
            import settings._

            if (!strictHostKeyChecking)
              ssh.addHostKeyVerifier(new PromiscuousVerifier)
            else
              knownHosts.map(new File(_)).foreach(ssh.loadKnownHosts)

            ssh.setTimeout(settings.timeOut)
            ssh.setConnectTimeout(settings.connectTimeOut)

            ssh.connect(host, port)

            sftpIdentity
              .fold(ssh.authPassword(credentials.username, credentials.password))(
                setIdentity(_, credentials.username)(ssh)
              )

            new SFtp(ssh.newSFTPClient(), blocker)
          })(client =>
            client.execute(_.close()).attempt.flatMap(_ => if (ssh.isConnected) CE.delay(ssh.disconnect()) else CE.unit)
          )
    } yield r

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
