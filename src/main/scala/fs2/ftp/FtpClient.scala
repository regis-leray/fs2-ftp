package fs2.ftp

import fs2.{ Pipe, Stream }

/**
 * Base trait of FtpClient which expose only safe methods
 * `F[_]` represents the effect type will be use
 *  `A` underlying ftp client instance type
 */
trait FtpClient[F[_], +A] {

  /**
   * Retreive information of a specific ftp resource like a file or directory
   * If the resource is not found we are returning an `Option.None`
   */
  def stat(path: String): F[Option[FtpResource]]

  /**
   * Read a file from a specific location path
   * If the resource is not found the operation will fail and fs2.Stream will Emit an IOException
   * in the error channel use `recover/recoverWith` to catch it
   */
  def readFile(path: String, chunkSize: Int = 2048): fs2.Stream[F, Byte]

  /**
   * Delete resource from a path
   * If the resource is not found the operation will fail and Emit an IOException in the error channel
   * use `recover/recoverWith` to catch it
   */
  def rm(path: String): F[Unit]

  /**
   * Delete a directory from a path
   *
   * If the resource is not found the operation will fail and Emit an IOException in the error channel
   * use `recover/recoverWith` to catch it
   */
  def rmdir(path: String): F[Unit]

  /**
   * Create a directory from a path
   *
   * If the resource already exist the operation will fail and emit an IOException in the error channel
   * use `recover/recoverWith` to catch it
   */
  def mkdir(path: String): F[Unit]

  /**
   * List all directories and files of a specific directory, it don't support nested directories
   * see `lsDescendant`
   *
   * If the directory dont exist it emits nothing,
   */
  def ls(path: String): Stream[F, FtpResource]

  /**
   * List only files by traversing nested directories
   * If the directory dont exist it emits nothing,
   */
  def lsDescendant(path: String): Stream[F, FtpResource]

  /**
   * Upload data to a specific location path
   * If operation failed it will emit an IOException in the error channel
   * use `recover/recoverWith` to catch it
   */
  def upload(path: String): Pipe[F, Byte, Unit]

  /**
   * Execute safely any operation supported by the underlying ftp client `A`
   * If operation failed it will emit an IOException in the error channel
   * use `recover/recoverWith` to catch it
   */
  def execute[T](f: A => T): F[T]
}
