package ray.fs2.ftp

import java.net.Proxy

import net.schmizz.sshj.sftp.{ SFTPClient => JSFTPClient }
import net.schmizz.sshj.{ Config => SshConfig, DefaultConfig => DefaultSshConfig }
import org.apache.commons.net.ftp.{ FTPClient => JFTPClient }

sealed trait FtpSettings[A]

object FtpSettings {
  final case class FtpCredentials(username: String, password: String)

  final case class SecureFtpSettings(
    host: String,
    port: Int,
    credentials: FtpCredentials,
    strictHostKeyChecking: Boolean,
    knownHosts: Option[String],
    sftpIdentity: Option[SftpIdentity],
    sshConfig: SshConfig
  ) extends FtpSettings[JSFTPClient]

  sealed trait SftpIdentity {
    type KeyType
    val privateKey: KeyType
    val privateKeyFilePassphrase: Option[Array[Byte]]
  }

  final case class RawKeySftpIdentity(
    privateKey: Array[Byte],
    privateKeyFilePassphrase: Option[Array[Byte]] = None,
    publicKey: Option[Array[Byte]] = None
  ) extends SftpIdentity {
    type KeyType = Array[Byte]
  }

  final case class KeyFileSftpIdentity(privateKey: String, privateKeyFilePassphrase: Option[Array[Byte]] = None)
      extends SftpIdentity {
    type KeyType = String
  }

  object SftpIdentity {

    def createRawSftpIdentity(privateKey: Array[Byte]): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey)

    def createRawSftpIdentity(privateKey: Array[Byte], privateKeyFilePassphrase: Array[Byte]): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey, Some(privateKeyFilePassphrase))

    def createRawSftpIdentity(
      privateKey: Array[Byte],
      privateKeyFilePassphrase: Array[Byte],
      publicKey: Array[Byte]
    ): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey, Some(privateKeyFilePassphrase), Some(publicKey))

    def createFileSftpIdentity(privateKey: String): KeyFileSftpIdentity =
      KeyFileSftpIdentity(privateKey)

    def createFileSftpIdentity(privateKey: String, privateKeyFilePassphrase: Array[Byte]): KeyFileSftpIdentity =
      KeyFileSftpIdentity(privateKey, Some(privateKeyFilePassphrase))
  }

  object SecureFtpSettings {

    def apply(host: String, port: Int, creds: FtpCredentials): SecureFtpSettings =
      new SecureFtpSettings(
        host,
        port,
        creds,
        strictHostKeyChecking = false,
        knownHosts = None,
        sftpIdentity = None,
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
    secure: Boolean
  ) extends FtpSettings[JFTPClient]

  object UnsecureFtpSettings {

    def apply(host: String, port: Int, creds: FtpCredentials): UnsecureFtpSettings =
      new UnsecureFtpSettings(host, port, creds, true, true, None, false)

    def secure(host: String, port: Int, creds: FtpCredentials): UnsecureFtpSettings =
      new UnsecureFtpSettings(host, port, creds, true, true, None, true)
  }
}
