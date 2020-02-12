package ray.fs2.ftp

import cats.effect.{ ContextShift, IO, Resource }
import fs2.Stream
import ray.fs2.ftp.FtpSettings.{ SecureFtpSettings, UnsecureFtpSettings }

trait FtpClient[+A] {
  def stat(path: String)(implicit cs: ContextShift[IO]): IO[Option[FtpResource]]
  def readFile(path: String, chunkSize: Int = 2048)(implicit cs: ContextShift[IO]): fs2.Stream[IO, Byte]
  def rm(path: String)(implicit cs: ContextShift[IO]): IO[Unit]
  def rmdir(path: String)(implicit cs: ContextShift[IO]): IO[Unit]
  def mkdir(path: String)(implicit cs: ContextShift[IO]): IO[Unit]
  def ls(path: String)(implicit cs: ContextShift[IO]): Stream[IO, FtpResource]
  def lsDescendant(path: String)(implicit cs: ContextShift[IO]): Stream[IO, FtpResource]
  def upload(path: String, source: fs2.Stream[IO, Byte])(implicit cs: ContextShift[IO]): IO[Unit]
  def execute[T](f: A => T)(implicit cs: ContextShift[IO]): IO[T]
}

object FtpClient {

  def connect[A](settings: FtpSettings[A])(implicit cs: ContextShift[IO]): Resource[IO, FtpClient[A]] = settings match {
    case s: UnsecureFtpSettings => Ftp.connect(s)
    case s: SecureFtpSettings   => SFtp.connect(s)
  }
}
