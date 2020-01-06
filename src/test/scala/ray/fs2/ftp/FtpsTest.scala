package ray.fs2.ftp

import ray.fs2.ftp.FtpSettings.{ FtpCredentials, UnsecureFtpSettings }

class FtpsTest extends BaseFtpTest {

  override val settings: UnsecureFtpSettings =
    UnsecureFtpSettings.secure("127.0.0.1", 2121, FtpCredentials("username", "userpass"))
}
