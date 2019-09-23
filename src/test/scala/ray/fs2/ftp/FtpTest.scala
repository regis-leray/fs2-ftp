package ray.fs2.ftp

import ray.fs2.ftp.settings.FtpCredentials.credentials
import ray.fs2.ftp.settings.FtpSettings

class FtpTest extends BaseFtpTest {
  override val settings = FtpSettings("localhost", port = 2121, credentials("username", "userpass"))
}
