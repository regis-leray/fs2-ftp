package ray.fs2

import cats.effect.{ ConcurrentEffect, ContextShift, Resource }
import ray.fs2.ftp.FtpSettings.{ SecureFtpSettings, UnsecureFtpSettings }

package object ftp {

  def connect[F[_]: ContextShift: ConcurrentEffect, A](settings: FtpSettings[A]): Resource[F, FtpClient[F, A]] =
    settings match {
      case s: UnsecureFtpSettings => UnsecureFtp.connect(s)
      case s: SecureFtpSettings   => SecureFtp.connect(s)
    }
}
