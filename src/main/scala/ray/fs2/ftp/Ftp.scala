package ray.fs2.ftp

import java.io.{ FileNotFoundException, InputStream }

import cats.effect.{ Async, Blocker, ConcurrentEffect, ContextShift, Resource }
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.monadError._
import fs2.Stream
import org.apache.commons.net.ftp._
import ray.fs2.ftp.settings.FtpSettings

import scala.concurrent.ExecutionContext

object Ftp {

  def connect[F[_]](settings: FtpSettings)(implicit F: Async[F]): Resource[F, FTPClient] =
    Resource.make {
      F.delay {
          val ftpClient = if (settings.secure) new FTPSClient() else new FTPClient()
          settings.proxy.foreach(ftpClient.setProxy)
          ftpClient.connect(settings.host, settings.port)

          val success = ftpClient.login(settings.credentials.username, settings.credentials.password)

          if (settings.binary) {
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
          }

          if (settings.passiveMode) {
            ftpClient.enterLocalPassiveMode()
          }

          success -> ftpClient
        }
        .ensure(new IllegalArgumentException(s"Fail to connect to server ${settings.host}:${settings.port}"))(_._1)
        .map(_._2)
    } { client =>
      for {
        connected <- F.delay(client.isConnected)
        _ <- if (!connected) F.pure(())
            else
              F.delay {
                client.logout()
                client.disconnect()
              }
      } yield ()
    }

  def stat[F[_]](path: String)(client: FTPClient)(implicit F: Async[F]): F[Option[FtpResource]] =
    F.delay(
      Option(client.mlistFile(path)).map(FtpResource(_))
    )

  def readFile[F[_]](path: String, chunkSize: Int = 2048)(
    client: FTPClient
  )(implicit ec: ExecutionContext, cs: ContextShift[F], F: Async[F]): fs2.Stream[F, Byte] = {
    val is = F
      .delay(Option(client.retrieveFileStream(path)))
      .flatMap(_.fold(F.raiseError[InputStream](new FileNotFoundException(s"file doesnt exist $path")))(F.pure))

    fs2.io.readInputStream(
      is,
      chunkSize,
      Blocker.liftExecutionContext(ec)
    )
  }

  def rm[F[_]](path: String)(client: FTPClient)(implicit F: Async[F]): F[Unit] =
    F.delay(client.deleteFile(path))
      .map(_ => ())

  def rmdir[F[_]](path: String)(client: FTPClient)(implicit F: Async[F]): F[Unit] =
    F.delay(client.removeDirectory(path))
      .map(_ => ())

  def mkdir[F[_]](path: String)(client: FTPClient)(implicit F: Async[F]): F[Unit] =
    F.delay(client.makeDirectory(path))
      .map(_ => ())

  def ls[F[_]](path: String)(client: FTPClient)(implicit F: Async[F]): Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(F.delay(client.listFiles(path).toList))
      .map(FtpResource(_, Some(path)))

  def lsDescendant[F[_]](path: String)(client: FTPClient)(implicit F: Async[F]): Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(F.delay(client.listFiles(path).toList))
      .flatMap { f =>
        if (f.isDirectory) {
          val dirPath = Option(path).filter(_.endsWith("/")).fold(s"$path/${f.getName}")(p => s"$p${f.getName}")
          lsDescendant(dirPath)(client)
        } else
          Stream(FtpResource(f, Some(path)))
      }

  def upload[F[_]: ConcurrentEffect](path: String, source: fs2.Stream[F, Byte])(
    client: FTPClient
  )(implicit F: Async[F]): F[Unit] =
    source
      .through(fs2.io.toInputStream)
      .evalMap(is => F.delay(client.storeFile(path, is)))
      .compile
      .drain
}
