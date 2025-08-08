package fs2.ftp

import java.io._
import cats.effect.{ Async, Resource }
import cats.syntax.applicativeError._
import fs2.{ Pipe, Stream }
import fs2.Stream._
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.{ OpenMode, Response, SFTPException, SFTPClient => JSFTPClient }
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import fs2.ftp.FtpSettings.{
  KeyCredentials,
  KeyFileSftpIdentity,
  PasswordCredentials,
  RawKeySftpIdentity,
  SecureFtpSettings,
  SftpIdentity
}

import scala.jdk.CollectionConverters._

final private class SecureFtp[F[_]: Async](unsafeClient: SecureFtp.Client, maxUnconfirmedWrites: Int)
    extends FtpClient[F, JSFTPClient] {

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

  def execute[T](f: JSFTPClient => T): F[T] =
    Async[F].blocking(f(unsafeClient))

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

      input <- fs2.io.readInputStream(Async[F].pure(is), chunkSize)
    } yield input

  def rm(path: String): F[Unit] =
    execute(_.rm(path))

  def rmdir(path: String): F[Unit] =
    execute(_.rmdir(path))

  def mkdir(path: String): F[Unit] =
    execute(_.mkdir(path))

  def upload(path: String): Pipe[F, Byte, Unit] =
    source =>
      (for {
        remoteFile <- Stream.eval(execute(_.open(path, java.util.EnumSet.of(OpenMode.WRITE, OpenMode.CREAT))))

        os: java.io.OutputStream = new remoteFile.RemoteFileOutputStream(0, maxUnconfirmedWrites) {

          override def close(): Unit =
            try {
              remoteFile.close()
            } finally {
              super.close()
            }
        }
        _ <- source.through(fs2.io.writeOutputStream(Async[F].pure(os)))
      } yield ())
}

object SecureFtp {

  type Client = JSFTPClient

  def apply[F[_]: Async](
    unsafeClient: Client,
    maxUnconfirmedWrites: Int = 0
  ): Resource[F, FtpClient[F, SecureFtp.Client]] =
    Resource.make(Async[F].pure(new SecureFtp[F](unsafeClient, maxUnconfirmedWrites))) { client =>
      client.execute(_.close()).voidError
    }

  def connect[F[_]: Async](
    settings: SecureFtpSettings
  ): Resource[F, FtpClient[F, SecureFtp.Client]] =
    for {
      ssh <- Resource.make(Async[F].delay(new SSHClient(settings.sshConfig)))(ssh =>
              Async[F].delay(if (ssh.isConnected) ssh.disconnect() else {}).voidError
            )
      r <- Resource.make[F, FtpClient[F, JSFTPClient]](Async[F].delay {
            import settings._

            if (!strictHostKeyChecking)
              ssh.addHostKeyVerifier(new PromiscuousVerifier)
            else
              knownHosts.map(new File(_)).foreach(ssh.loadKnownHosts)

            ssh.setTimeout(settings.timeOut)
            ssh.setConnectTimeout(settings.connectTimeOut)

            ssh.connect(host, port)

            credentials match {
              case PasswordCredentials(username, password) => ssh.authPassword(username, password)
              case KeyCredentials(username, identity)      => setIdentity(identity, username)(ssh)
            }

            new SecureFtp(ssh.newSFTPClient(), settings.maxUnconfirmedWrites)
          })(client => client.execute(_.close()).voidError)
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
