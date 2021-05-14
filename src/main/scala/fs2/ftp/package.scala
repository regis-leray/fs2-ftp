package fs2

import cats.effect.{ Async, Resource }
import fs2.ftp.FtpSettings.{ SecureFtpSettings, UnsecureFtpSettings }

package object ftp {

  /**
   * Create a safe FTP/SFTP client resource
   * Required to provide a ContextShift, since all operation will be executed into a dedicated thread pool
   *
   * If operation failed, it emit a  `fs2.ftp.ConnectionError` with the cause of the failure
   */
  def connect[F[_]: Async, A](settings: FtpSettings[A]): Resource[F, FtpClient[F, A]] =
    settings match {
      case s: UnsecureFtpSettings => UnsecureFtp.connect(s)
      case s: SecureFtpSettings   => SecureFtp.connect(s)
    }
}
