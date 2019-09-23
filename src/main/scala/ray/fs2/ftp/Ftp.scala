package ray.fs2.ftp

import java.nio.file.attribute.PosixFilePermission

import cats.effect.{Async, ConcurrentEffect, ContextShift}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.monadError._
import fs2.Stream
import org.apache.commons.net.ftp.{FTP, FTPClient, FTPFile, FTPSClient}
import ray.fs2.ftp.settings.FtpSettings

import scala.concurrent.ExecutionContext

object Ftp {
  def connect[F[_]](settings: FtpSettings)(implicit F: Async[F]): F[FTPClient] = F.delay {
    val ftpClient = if (settings.secure) new FTPSClient() else new FTPClient()
    settings.proxy.foreach(ftpClient.setProxy)
    ftpClient.connect(settings.host, settings.port)
    settings.configureConnection(ftpClient)

    ftpClient.login(settings.credentials.username, settings.credentials.password)

    if (settings.binary) {
      ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
    }

    if (settings.passiveMode) {
      ftpClient.enterLocalPassiveMode()
    }

    ftpClient
  }

  def disconnect[F[_]](client: FTPClient)(implicit F: Async[F]): F[Unit] = for {
    connected <- F.delay(client.isConnected)
    _ <- if (!connected) F.pure(()) else
      F.delay {
        client.logout()
        client.disconnect()
      }
  } yield ()

  def stat[F[_]](client: FTPClient)(path: String)(implicit F: Async[F]): F[Option[FtpFile]] =
    F.delay(
      Option(client.mlistFile(path)).map(FtpFileOps.apply)
    )

  def readFile[F[_]](client: FTPClient, chunkSize: Int = 2048)(path: String)(implicit ec: ExecutionContext, cs: ContextShift[F], F: Async[F]): fs2.Stream[F, Byte] = {
    val stream = client.retrieveFileStream(path)
    fs2.io.readInputStream(F.delay(stream), chunkSize, ec)
  }

  def rm[F[_]](client: FTPClient)(path: String)(implicit F: Async[F]): F[Unit] =
    F.delay(client.deleteFile(path))
      .ensure(new IllegalArgumentException(s"Path is invalid. Cannot delete file : $path"))(identity)
      .map(_ => ())

  def rmdir[F[_]](client: FTPClient)(path: String)(implicit F: Async[F]): F[Unit] =
    F.delay(client.removeDirectory(path))
      .ensure(new IllegalArgumentException(s"Path is invalid. Cannot delete directory : $path"))(identity)
      .map(_ => ())

  def mkdir[F[_]](client: FTPClient)(path: String)(implicit F: Async[F]): F[Unit] =
    F.delay(client.makeDirectory(path))
      .ensure(new IllegalArgumentException(s"Path is invalid. Cannot create directory : $path"))(identity)
      .map(_ => ())

  def listFiles[F[_]](client: FTPClient)(basePath: String, predicate: FtpFile => Boolean = _ => true)(implicit F: Async[F]): Stream[F, FtpFile] = {
    val rootPath = if (!basePath.isEmpty && basePath.head != '/') s"/$basePath" else basePath

    fs2.Stream.eval(F.delay(client.listFiles(rootPath, (file: FTPFile) => {
      predicate(FtpFileOps(file))
    }).toList))
      .flatMap(Stream.emits)
      .flatMap { f =>
        if (f.isDirectory)
          listFiles(client)(f.getName)
        else
          Stream(FtpFileOps(f))
      }
  }

  def upload[F[_] : ConcurrentEffect](client: FTPClient)(path: String, source: fs2.Stream[F, Byte])(implicit F: Async[F]): F[Unit] =
    source.through(fs2.io.toInputStream)
      .evalMap(is =>
        F.delay(client.storeFile(path, is))
      )
      .ensure(new IllegalArgumentException(s"Path is invalid. Cannot upload data to : $path"))(identity)
      .compile.drain


  object FtpFileOps {
    def apply(f: FTPFile): FtpFile = {
      val fileName = f.getName.substring(f.getName.lastIndexOf("/") + 1, f.getName.length)
      FtpFile(fileName, f.getName, f.getSize, f.getTimestamp.getTimeInMillis, getPosixFilePermissions(f))
    }

    private def getPosixFilePermissions(file: FTPFile) =
      Map(
        PosixFilePermission.OWNER_READ -> file.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION),
        PosixFilePermission.OWNER_WRITE -> file.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION),
        PosixFilePermission.OWNER_EXECUTE -> file.hasPermission(FTPFile.USER_ACCESS, FTPFile.EXECUTE_PERMISSION),
        PosixFilePermission.GROUP_READ -> file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.READ_PERMISSION),
        PosixFilePermission.GROUP_WRITE -> file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.WRITE_PERMISSION),
        PosixFilePermission.GROUP_EXECUTE -> file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.EXECUTE_PERMISSION),
        PosixFilePermission.OTHERS_READ -> file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.READ_PERMISSION),
        PosixFilePermission.OTHERS_WRITE -> file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.WRITE_PERMISSION),
        PosixFilePermission.OTHERS_EXECUTE -> file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.EXECUTE_PERMISSION)
      ).collect {
        case (perm, true) ⇒ perm
      }.toSet

  }

}
