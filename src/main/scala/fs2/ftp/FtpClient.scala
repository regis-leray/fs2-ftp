package fs2.ftp

import fs2.Stream

trait FtpClient[F[_], +A] {
  def stat(path: String): F[Option[FtpResource]]
  def readFile(path: String, chunkSize: Int = 2048): fs2.Stream[F, Byte]
  def rm(path: String): F[Unit]
  def rmdir(path: String): F[Unit]
  def mkdir(path: String): F[Unit]
  def ls(path: String): Stream[F, FtpResource]
  def lsDescendant(path: String): Stream[F, FtpResource]
  def upload(path: String, source: fs2.Stream[F, Byte]): F[Unit]
  def execute[T](f: A => T): F[T]
}
