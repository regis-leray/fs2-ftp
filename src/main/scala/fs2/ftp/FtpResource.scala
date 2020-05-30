package fs2.ftp

import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission._

import net.schmizz.sshj.sftp.{ FileAttributes, RemoteResourceInfo }
import net.schmizz.sshj.xfer.FilePermission._
import org.apache.commons.net.ftp.FTPFile

import scala.jdk.CollectionConverters._

final case class FtpResource(
  path: String,
  size: Long,
  lastModified: Long,
  permissions: Set[PosixFilePermission],
  isDirectory: Option[Boolean]
)

object FtpResource {

  def apply(f: FTPFile, path: Option[String] = None): FtpResource =
    FtpResource(
      path.fold(f.getName) {
        case "/" => s"/${f.getName}"
        case p   => s"$p/${f.getName}"
      },
      f.getSize,
      f.getTimestamp.getTimeInMillis,
      getPosixFilePermissions(f),
      Some(f.isDirectory)
    )

  def apply(file: RemoteResourceInfo): FtpResource =
    FtpResource(
      file.getPath,
      file.getAttributes.getSize,
      file.getAttributes.getMtime,
      posixFilePermissions(file.getAttributes),
      Some(file.isDirectory)
    )

  def apply(path: String, attr: FileAttributes): FtpResource =
    FtpResource(path, attr.getSize, attr.getMtime, posixFilePermissions(attr), None)

  private def getPosixFilePermissions(file: FTPFile) =
    Map(
      PosixFilePermission.OWNER_READ     -> file.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION),
      PosixFilePermission.OWNER_WRITE    -> file.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION),
      PosixFilePermission.OWNER_EXECUTE  -> file.hasPermission(FTPFile.USER_ACCESS, FTPFile.EXECUTE_PERMISSION),
      PosixFilePermission.GROUP_READ     -> file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.READ_PERMISSION),
      PosixFilePermission.GROUP_WRITE    -> file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.WRITE_PERMISSION),
      PosixFilePermission.GROUP_EXECUTE  -> file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.EXECUTE_PERMISSION),
      PosixFilePermission.OTHERS_READ    -> file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.READ_PERMISSION),
      PosixFilePermission.OTHERS_WRITE   -> file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.WRITE_PERMISSION),
      PosixFilePermission.OTHERS_EXECUTE -> file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.EXECUTE_PERMISSION)
    ).collect {
      case (perm, true) => perm
    }.toSet

  private val posixFilePermissions: FileAttributes => Set[PosixFilePermission] = { attr =>
    attr.getPermissions.asScala.collect {
      case USR_R => OWNER_READ
      case USR_W => OWNER_WRITE
      case USR_X => OWNER_EXECUTE
      case GRP_R => GROUP_READ
      case GRP_W => GROUP_WRITE
      case GRP_X => GROUP_EXECUTE
      case OTH_R => OTHERS_READ
      case OTH_W => OTHERS_WRITE
      case OTH_X => OTHERS_EXECUTE
    }.toSet
  }
}
