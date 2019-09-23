package ray.fs2.ftp

import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Path, Paths}

import scala.util.Try

final case class FtpFile(name: String,
                         //TODO is path contain name if not provide absolutePath = path + name
                         path: String,
                         size: Long,
                         lastModified: Long,
                         permissions: Set[PosixFilePermission]) {

  val absolutePath: Option[Path] = Try(Paths.get(path)).toOption
}
