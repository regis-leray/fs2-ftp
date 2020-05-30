package fs2.ftp

import fs2.ftp.FtpSettings.{ FtpCredentials, UnsecureFtpSettings }

class UnsecureFtpTest extends BaseFtpTest {
  override val settings = UnsecureFtpSettings("127.0.0.1", port = 2121, FtpCredentials("username", "userpass"))
}
