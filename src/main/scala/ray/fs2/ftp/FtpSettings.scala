package ray.fs2.ftp

import java.net.Proxy
import java.nio.file.Path

import net.schmizz.sshj.sftp.{ SFTPClient => JSFTPClient }
import net.schmizz.sshj.{ Config => SshConfig, DefaultConfig => DefaultSshConfig }
import org.apache.commons.net.ftp.{ FTPClient => JFTPClient }

sealed trait FtpSettings[A]

object FtpSettings {

  final case class FtpCredentials(username: String, password: String)

  sealed trait SftpIdentity {
    type KeyType
    val privateKey: KeyType
    val passphrase: Option[String]
  }

  final case class RawKeySftpIdentity(privateKey: String, passphrase: Option[String], publicKey: Option[String])
      extends SftpIdentity {
    type KeyType = String
  }

  object RawKeySftpIdentity {

    def apply(privateKey: String): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey, None, None)

    def apply(privateKey: String, passphrase: String): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey, Some(passphrase), None)
  }

  final case class KeyFileSftpIdentity(privateKey: Path, passphrase: Option[String]) extends SftpIdentity {
    type KeyType = Path
  }

  object KeyFileSftpIdentity {

    def apply(privateKey: Path): KeyFileSftpIdentity =
      KeyFileSftpIdentity(privateKey, None)
  }

  final case class SecureFtpSettings(
    host: String,
    port: Int,
    credentials: FtpCredentials,
    sftpIdentity: Option[SftpIdentity],
    strictHostKeyChecking: Boolean,
    knownHosts: Option[String],
    timeOut: Int,
    connectTimeOut: Int,
    sshConfig: SshConfig
  ) extends FtpSettings[JSFTPClient]

  object SecureFtpSettings {

    def apply(host: String, port: Int, credentials: FtpCredentials): SecureFtpSettings =
      new SecureFtpSettings(
        host,
        port,
        credentials,
        sftpIdentity = None,
        strictHostKeyChecking = false,
        knownHosts = None,
        0,
        0,
        new DefaultSshConfig()
      )

    def apply(host: String, port: Int, credentials: FtpCredentials, identity: SftpIdentity): SecureFtpSettings =
      new SecureFtpSettings(
        host,
        port,
        credentials,
        sftpIdentity = Some(identity),
        strictHostKeyChecking = false,
        knownHosts = None,
        0,
        0,
        new DefaultSshConfig()
      )
  }

  final case class UnsecureFtpSettings(
    host: String,
    port: Int,
    credentials: FtpCredentials,
    binary: Boolean,
    passiveMode: Boolean,
    proxy: Option[Proxy],
    secure: Boolean,
    timeOut: Int,
    connectTimeOut: Int
  ) extends FtpSettings[JFTPClient]

  object UnsecureFtpSettings {

    def apply(host: String, port: Int, creds: FtpCredentials): UnsecureFtpSettings =
      new UnsecureFtpSettings(host, port, creds, true, true, None, false, 0, 0)

    def secure(host: String, port: Int, creds: FtpCredentials): UnsecureFtpSettings =
      new UnsecureFtpSettings(host, port, creds, true, true, None, true, 0, 0)
  }

}
