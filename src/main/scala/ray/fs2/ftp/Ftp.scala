package ray.fs2.ftp

import java.io.{ FileNotFoundException, InputStream }

import cats.effect.{ Blocker, ConcurrentEffect, ContextShift, IO, Resource }
import cats.syntax.monadError._
import fs2.Stream
import org.apache.commons.net.ftp.{ FTP, FTPSClient, FTPClient => JFTPClient }
import ray.fs2.ftp.FtpSettings.UnsecureFtpSettings

import scala.concurrent.ExecutionContext

final private class Ftp(unsafeClient: JFTPClient) extends FtpClient[JFTPClient] {

  def stat(path: String)(implicit ec: ExecutionContext): IO[Option[FtpResource]] =
    execute(client => Option(client.mlistFile(path)).map(FtpResource(_)))

  def readFile(
    path: String,
    chunkSize: Int = 2048
  )(implicit ec: ExecutionContext, cs: ContextShift[IO]): fs2.Stream[IO, Byte] = {
    val is = execute(client => Option(client.retrieveFileStream(path)))
      .flatMap(_.fold(IO.raiseError[InputStream](new FileNotFoundException(s"file doesnt exist $path")))(IO.pure))

    fs2.io.readInputStream(
      is,
      chunkSize,
      Blocker.liftExecutionContext(ec)
    )
  }

  def rm(path: String)(implicit ec: ExecutionContext): IO[Unit] =
    execute(_.deleteFile(path))
      .ensure(InvalidPathError(s"Path is invalid. Cannot delete file : $path"))(identity)
      .map(_ => ())

  def rmdir(path: String)(implicit ec: ExecutionContext): IO[Unit] =
    execute(_.removeDirectory(path))
      .ensure(InvalidPathError(s"Path is invalid. Cannot remove directory : $path"))(identity)
      .map(_ => ())

  def mkdir(path: String)(implicit ec: ExecutionContext): IO[Unit] =
    execute(_.makeDirectory(path))
      .ensure(InvalidPathError(s"Path is invalid. Cannot create directory : $path"))(identity)
      .map(_ => ())

  def ls(path: String)(implicit ec: ExecutionContext): Stream[IO, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.listFiles(path).toList))
      .map(FtpResource(_, Some(path)))

  def lsDescendant(path: String)(implicit ec: ExecutionContext): Stream[IO, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.listFiles(path).toList))
      .flatMap { f =>
        if (f.isDirectory) {
          val dirPath = Option(path).filter(_.endsWith("/")).fold(s"$path/${f.getName}")(p => s"$p${f.getName}")
          lsDescendant(dirPath)
        } else
          Stream(FtpResource(f, Some(path)))
      }

  def upload(
    path: String,
    source: fs2.Stream[IO, Byte]
  )(implicit ec: ExecutionContext, cs: ContextShift[IO]): IO[Unit] = {
    //TODO can we remove it ???
    implicit val F = ConcurrentEffect[IO]
    source
      .through(fs2.io.toInputStream[IO])
      .evalMap(
        is =>
          execute(_.storeFile(path, is))
            .ensure(InvalidPathError(s"Path is invalid. Cannot upload data to : $path"))(identity)
      )
      .compile
      .drain
  }

  def execute[T](f: JFTPClient => T)(implicit ec: ExecutionContext): IO[T] =
    Blocker[IO].use(
      _.blockOn(IO.delay(f(unsafeClient)))(IO.contextShift(ec))
    )
}

object Ftp {

  def connect(settings: UnsecureFtpSettings)(implicit ec: ExecutionContext): Resource[IO, FtpClient[JFTPClient]] =
    Resource.make[IO, FtpClient[JFTPClient]] {
      IO.delay {
          val ftpClient = if (settings.secure) new FTPSClient() else new JFTPClient()
          settings.proxy.foreach(ftpClient.setProxy)
          ftpClient.connect(settings.host, settings.port)

          val success = ftpClient.login(settings.credentials.username, settings.credentials.password)

          if (settings.binary) {
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
          }

          if (settings.passiveMode) {
            ftpClient.enterLocalPassiveMode()
          }

          success -> new Ftp(ftpClient)
        }
        .ensure(ConnectionError(s"Fail to connect to server ${settings.host}:${settings.port}"))(_._1)
        .map(_._2)
    } { client =>
      for {
        connected <- client.execute(_.isConnected)
        _ <- if (!connected) IO.pure(())
            else
              client
                .execute(_.logout)
                .attempt
                .flatMap(_ => client.execute(_.disconnect))
      } yield ()
    }
}
