package fs2.ftp

import fs2.ftp.FtpSettings.{ FtpCredentials, UnsecureFtpSettings }

class UnsecureFtpSslTest extends BaseFtpTest {

  override val settings: UnsecureFtpSettings =
    UnsecureFtpSettings.ssl("127.0.0.1", 2121, FtpCredentials("username", "userpass"))
}
