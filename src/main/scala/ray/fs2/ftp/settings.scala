package ray.fs2.ftp

import java.net.Proxy
import org.apache.commons.net.ftp.FTPClient

object settings {

  sealed trait FtpCredentials {
    def username: String
    def password: String
  }

  object FtpCredentials {
    def credentials(_username: String, _password: String): FtpCredentials = new FtpCredentials {
      val username: String = _username
      val password: String = _password
    }
  }

  final case class SFtpSettings(host: String,
                                port: Int,
                                credentials: FtpCredentials,
                                strictHostKeyChecking: Boolean,
                                knownHosts: Option[String],
                                sftpIdentity: Option[SftpIdentity])

  object SFtpSettings {
    def apply(host: String, port: Int, creds: FtpCredentials): SFtpSettings = new SFtpSettings(host,
      port,
      creds,
      strictHostKeyChecking = false,
      knownHosts = None,
      sftpIdentity = None
    )
  }

  sealed trait SftpIdentity {
    type KeyType
    val privateKey: KeyType
    val privateKeyFilePassphrase: Option[Array[Byte]]
  }

  final case class RawKeySftpIdentity(privateKey: Array[Byte],
                                      privateKeyFilePassphrase: Option[Array[Byte]] = None,
                                      publicKey: Option[Array[Byte]] = None) extends SftpIdentity {
    type KeyType = Array[Byte]
  }

  final case class KeyFileSftpIdentity(privateKey: String,
                                       privateKeyFilePassphrase: Option[Array[Byte]] = None) extends SftpIdentity {
    type KeyType = String
  }

  object SftpIdentity {
    def createRawSftpIdentity(privateKey: Array[Byte]): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey)

    def createRawSftpIdentity(privateKey: Array[Byte], privateKeyFilePassphrase: Array[Byte]): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey, Some(privateKeyFilePassphrase))

    def createRawSftpIdentity(privateKey: Array[Byte], privateKeyFilePassphrase: Array[Byte], publicKey: Array[Byte]): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey, Some(privateKeyFilePassphrase), Some(publicKey))

    def createFileSftpIdentity(privateKey: String): KeyFileSftpIdentity =
      KeyFileSftpIdentity(privateKey)

    def createFileSftpIdentity(privateKey: String, privateKeyFilePassphrase: Array[Byte]): KeyFileSftpIdentity =
      KeyFileSftpIdentity(privateKey, Some(privateKeyFilePassphrase))
  }


  final case class FtpSettings(host: String,
                               port: Int,
                               credentials: FtpCredentials,
                               binary: Boolean,
                               passiveMode: Boolean,
                               configureConnection: FTPClient => Unit,
                               proxy: Option[Proxy],
                               secure: Boolean)

  object FtpSettings {
    def apply(host: String, port: Int, creds: FtpCredentials): FtpSettings =
      new FtpSettings(host, port, creds, true, true, _ => (), None, false)

    def secure(host: String, port: Int, creds: FtpCredentials): FtpSettings =
      new FtpSettings(host, port, creds, true, true, _ => (), None, true)
  }

}
