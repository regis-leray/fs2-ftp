package ray.fs2.ftp

import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission._

import cats.effect.{Async, ConcurrentEffect, ContextShift}
import cats.syntax.applicativeError._
import cats.syntax.functor._
import fs2.Stream
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.{FileAttributes, OpenMode, RemoteResourceFilter, RemoteResourceInfo, Response, SFTPClient, SFTPException}
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.xfer.FilePermission._
import ray.fs2.ftp.settings.{KeyFileSftpIdentity, RawKeySftpIdentity, SFtpSettings, SftpIdentity}
import cats.syntax.either._
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

object SFtp {
  def connect[F[_]](settings: SFtpSettings)(implicit ssh: SSHClient, F: Async[F]): F[SFTPClient] = F.delay {
    import settings._

    if (!strictHostKeyChecking)
      ssh.addHostKeyVerifier(new PromiscuousVerifier)
    else
      knownHosts.map(new File(_)).foreach(ssh.loadKnownHosts)

    ssh.connect(host, port)

    if (credentials.password != "" && sftpIdentity.isEmpty)
      ssh.authPassword(credentials.username, credentials.password)

    sftpIdentity.foreach(setIdentity(_, credentials.username))

    ssh.newSFTPClient()
  }

  private[this] def setIdentity(identity: SftpIdentity, username: String)(implicit ssh: SSHClient): Unit = {
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

  def disconnect[F[_]](client: SFTPClient)(implicit ssh: SSHClient, F: Async[F]): F[Unit] = F.delay {
    client.close()
    if (ssh.isConnected) ssh.disconnect()
  }

  def listFiles[F[_]](client: SFTPClient)(basePath: String, predicate: FtpFile => Boolean = _ => true)(implicit F: Async[F]): fs2.Stream[F, FtpFile] = {
    val path = if (!basePath.isEmpty && basePath.head != '/') s"/$basePath" else basePath
    val filter = new RemoteResourceFilter {
      override def accept(r: RemoteResourceInfo): Boolean =  predicate(SftpFileOps(r))
    }
    fs2.Stream.eval(F.delay(client.ls(path, filter).asScala))
      .flatMap(Stream.emits)
      .flatMap(f => if (f.isDirectory) listFiles(client)(f.getPath) else Stream(SftpFileOps(f)))
      .recoverWith{
        case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE => fs2.Stream.empty.covary[F]
        case other => fs2.Stream.raiseError[F](other)
      }
  }

  def stat[F[_]](client: SFTPClient)(path: String)(implicit F: Async[F]): F[Option[FtpFile]] =
    F.delay(client.stat(path))
      .attempt
      .map(r =>
        r.map(r => SftpFileOps(path, r)).toOption
      )

  def readFile[F[_]](client: SFTPClient, chunkSize: Int = 2048)(path: String)(implicit ec: ExecutionContext, cs: ContextShift[F], F: Async[F]): fs2.Stream[F, Byte] = for {
    remoteFile <- Stream.eval(F.delay(client.open(path, java.util.EnumSet.of(OpenMode.READ))))

    is: java.io.InputStream = new remoteFile.RemoteFileInputStream(0L) {
      override def close(): Unit =
        try {
          super.close()
        } finally {
          remoteFile.close()
        }
    }

    input <- fs2.io.readInputStream(F.pure(is), chunkSize, ec)
  } yield input

  def rm[F[_]](client: SFTPClient)(path: String)(implicit F: Async[F]): F[Unit] = F.delay(client.rm(path))

  def rmdir[F[_]](client: SFTPClient)(path: String)(implicit F: Async[F]): F[Unit] = F.delay(client.rmdir(path))

  def mkdirs[F[_]](client: SFTPClient)(path: String)(implicit F: Async[F]): F[Unit] = F.delay(client.mkdirs(path))

  def upload[F[_] : ConcurrentEffect: ContextShift](client: SFTPClient)(path: String, source: fs2.Stream[F, Byte])(implicit F: Async[F], ec: ExecutionContext): F[Unit] = (for {
    remoteFile <- Stream.eval(F.delay(client.open(path, java.util.EnumSet.of(OpenMode.WRITE, OpenMode.CREAT))))

    os: java.io.OutputStream = new remoteFile.RemoteFileOutputStream() {
      override def close(): Unit = {
        try {
          remoteFile.close()
        } finally {
          super.close()
        }
      }
    }
    _ <- source.through(fs2.io.writeOutputStream(F.pure(os), ec))
  } yield ()).compile.drain

  object SftpFileOps {
    def apply(file: RemoteResourceInfo): FtpFile = FtpFile(
      file.getName,
      file.getPath,
      file.getAttributes.getSize,
      file.getAttributes.getMtime,
      posixFilePermissions(file.getAttributes))

    def apply(path: String, attr: FileAttributes): FtpFile =
      FtpFile(path.substring(path.lastIndexOf("/") + 1, path.length), path, attr.getSize, attr.getMtime, posixFilePermissions(attr))

    private val posixFilePermissions: FileAttributes => Set[PosixFilePermission] = { attr =>
      attr.getPermissions.asScala.collect {
        case USR_R => OWNER_READ
        case USR_W => OWNER_WRITE
        case USR_X => OWNER_EXECUTE
        case GRP_R => GROUP_READ
        case GRP_W => GROUP_WRITE
        case GRP_X => GROUP_EXECUTE
        case OTH_R => OTHERS_READ
        case OTH_W => OTHERS_WRITE
        case OTH_X => OTHERS_EXECUTE
      }.toSet
    }
  }

}