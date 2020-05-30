package ray.fs2.ftp

import java.io.{ FileNotFoundException, InputStream }

import cats.effect.{ Blocker, ConcurrentEffect, ContextShift, Resource }
import cats.implicits._
import fs2.Stream
import org.apache.commons.net.ftp.{ FTP, FTPSClient, FTPClient => JFTPClient }
import ray.fs2.ftp.FtpSettings.UnsecureFtpSettings

final private class Ftp[F[_]](unsafeClient: JFTPClient, blocker: Blocker)(
  implicit CE: ConcurrentEffect[F],
  CS: ContextShift[F]
) extends FtpClient[F, JFTPClient] {

  def stat(path: String): F[Option[FtpResource]] =
    execute(client => Option(client.mlistFile(path)).map(FtpResource(_)))

  def readFile(path: String, chunkSize: Int = 2048): fs2.Stream[F, Byte] = {
    val is = execute(client => Option(client.retrieveFileStream(path)))
      .flatMap(
        _.fold(CE.raiseError[InputStream](new FileNotFoundException(s"file doesnt exist $path")))(x => CE.pure(x))
      )

    fs2.io.readInputStream(is, chunkSize, blocker)
  }

  def rm(path: String): F[Unit] =
    execute(_.deleteFile(path))
      .ensure(InvalidPathError(s"Path is invalid. Cannot delete file : $path"))(identity)
      .map(_ => ())

  def rmdir(path: String): F[Unit] =
    execute(_.removeDirectory(path))
      .ensure(InvalidPathError(s"Path is invalid. Cannot remove directory : $path"))(identity)
      .map(_ => ())

  def mkdir(path: String): F[Unit] =
    execute(_.makeDirectory(path))
      .ensure(InvalidPathError(s"Path is invalid. Cannot create directory : $path"))(identity)
      .map(_ => ())

  def ls(path: String): Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.listFiles(path).toList))
      .map(FtpResource(_, Some(path)))

  def lsDescendant(path: String): Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.listFiles(path).toList))
      .flatMap { f =>
        if (f.isDirectory) {
          val dirPath = Option(path).filter(_.endsWith("/")).fold(s"$path/${f.getName}")(p => s"$p${f.getName}")
          lsDescendant(dirPath)
        } else
          Stream(FtpResource(f, Some(path)))
      }

  def upload(path: String, source: fs2.Stream[F, Byte]): F[Unit] =
    source
      .through(fs2.io.toInputStream[F])
      .evalMap(is =>
        execute(_.storeFile(path, is))
          .ensure(InvalidPathError(s"Path is invalid. Cannot upload data to : $path"))(identity)
      )
      .compile
      .drain

  def execute[T](f: JFTPClient => T): F[T] =
    blocker.delay[F, T](f(unsafeClient))
}

object Ftp {

  def connect[F[_]](
    settings: UnsecureFtpSettings
  )(implicit CS: ContextShift[F], CE: ConcurrentEffect[F]): Resource[F, FtpClient[F, JFTPClient]] =
    for {
      blocker <- Blocker[F]

      r <- Resource.make[F, FtpClient[F, JFTPClient]] {
            CE.delay {
                val ftpClient = if (settings.secure) new FTPSClient() else new JFTPClient()
                settings.proxy.foreach(ftpClient.setProxy)
                ftpClient.connect(settings.host, settings.port)

                val success = ftpClient.login(settings.credentials.username, settings.credentials.password)

                if (settings.binary) {
                  ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
                }

                ftpClient.setConnectTimeout(settings.connectTimeOut)
                ftpClient.setDefaultTimeout(settings.timeOut)

                if (settings.passiveMode) {
                  ftpClient.enterLocalPassiveMode()
                }

                success -> new Ftp[F](ftpClient, blocker)
              }
              .ensure(ConnectionError(s"Fail to connect to server ${settings.host}:${settings.port}"))(_._1)
              .map(_._2)
          } { client =>
            for {
              connected <- client.execute(_.isConnected)
              _ <- if (!connected) CE.pure(())
                  else
                    client
                      .execute(_.logout)
                      .attempt
                      .flatMap(_ => client.execute(_.disconnect))
            } yield ()
          }

    } yield r

}
