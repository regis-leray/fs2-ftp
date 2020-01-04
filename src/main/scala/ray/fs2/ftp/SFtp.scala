package ray.fs2.ftp

import java.io._

import cats.effect.{ Async, Blocker, ConcurrentEffect, ContextShift, Resource }
import cats.syntax.applicativeError._
import fs2.Stream
import fs2.Stream._
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp._
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import ray.fs2.ftp.settings.{ KeyFileSftpIdentity, RawKeySftpIdentity, SFtpSettings, SftpIdentity }

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

object SFtp {

  def connect[F[_]](settings: SFtpSettings)(implicit F: Async[F]): Resource[F, SFTPClient] = {
    val ssh = new SSHClient(settings.sshConfig)

    Resource.make(F.delay {
      import settings._

      if (!strictHostKeyChecking)
        ssh.addHostKeyVerifier(new PromiscuousVerifier)
      else
        knownHosts.map(new File(_)).foreach(ssh.loadKnownHosts)

      ssh.connect(host, port)

      if (credentials.password != "" && sftpIdentity.isEmpty)
        ssh.authPassword(credentials.username, credentials.password)

      sftpIdentity.foreach(setIdentity(_, credentials.username)(ssh))

      ssh.newSFTPClient()
    })(
      client =>
        F.delay {
          client.close()
          if (ssh.isConnected) ssh.disconnect()
        }
    )
  }

  private[this] def setIdentity(identity: SftpIdentity, username: String)(ssh: SSHClient): Unit = {
    def bats(array: Array[Byte]): String = new String(array, "UTF-8")

    def initKey(f: OpenSSHKeyFile => Unit): Unit = {
      val key = new OpenSSHKeyFile
      f(key)
      ssh.authPublickey(username, key)
    }

    val passphrase =
      identity.privateKeyFilePassphrase.map(pass => PasswordUtils.createOneOff(bats(pass).toCharArray)).orNull

    identity match {
      case id: RawKeySftpIdentity =>
        initKey(_.init(bats(id.privateKey), id.publicKey.map(bats).orNull, passphrase))
      case id: KeyFileSftpIdentity =>
        initKey(_.init(new File(id.privateKey), passphrase))
    }
  }

  def ls[F[_]](path: String)(client: SFTPClient)(implicit F: Async[F]): fs2.Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(F.delay(client.ls(path).asScala.toSeq))
      .map(FtpResource(_))
      .recoverWith {
        case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE => fs2.Stream.empty.covary[F]
        case other                                                                     => fs2.Stream.raiseError[F](other)
      }

  def lsDescendant[F[_]](path: String)(client: SFTPClient)(implicit F: Async[F]): fs2.Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(F.delay(client.ls(path).asScala.toSeq))
      .flatMap(f => if (f.isDirectory) lsDescendant(f.getPath)(client) else Stream(FtpResource(f)))
      .recoverWith {
        case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE => fs2.Stream.empty.covary[F]
        case other                                                                     => fs2.Stream.raiseError[F](other)
      }

  def stat[F[_]](path: String)(client: SFTPClient)(implicit F: Async[F]): F[Option[FtpResource]] =
    F.delay(
      Option(client.statExistence(path)).map(FtpResource(path, _))
    )

  def readFile[F[_]](path: String, chunkSize: Int = 10 * 1024)(
    client: SFTPClient
  )(implicit ec: ExecutionContext, cs: ContextShift[F], F: Async[F]): fs2.Stream[F, Byte] =
    for {
      remoteFile <- Stream.eval(F.delay(client.open(path, java.util.EnumSet.of(OpenMode.READ))))

      is: java.io.InputStream = new remoteFile.ReadAheadRemoteFileInputStream(64) {

        override def close(): Unit =
          try {
            super.close()
          } finally {
            remoteFile.close()
          }
      }

      input <- fs2.io.readInputStream(F.pure(is), chunkSize, Blocker.liftExecutionContext(ec))
    } yield input

  def rm[F[_]](path: String)(client: SFTPClient)(implicit F: Async[F]): F[Unit] =
    F.delay(client.rm(path))

  def rmdir[F[_]](path: String)(client: SFTPClient)(implicit F: Async[F]): F[Unit] =
    F.delay(client.rmdir(path))

  def mkdirs[F[_]](path: String)(client: SFTPClient)(implicit F: Async[F]): F[Unit] =
    F.delay(client.mkdirs(path))

  def upload[F[_]: ConcurrentEffect: ContextShift](path: String, source: fs2.Stream[F, Byte])(
    client: SFTPClient
  )(implicit F: Async[F], ec: ExecutionContext): F[Unit] =
    (for {
      remoteFile <- Stream.eval(F.delay(client.open(path, java.util.EnumSet.of(OpenMode.WRITE, OpenMode.CREAT))))

      os: java.io.OutputStream = new remoteFile.RemoteFileOutputStream() {

        override def close(): Unit =
          try {
            remoteFile.close()
          } finally {
            super.close()
          }
      }
      _ <- source.through(fs2.io.writeOutputStream(F.pure(os), Blocker.liftExecutionContext(ec)))
    } yield ()).compile.drain
}
