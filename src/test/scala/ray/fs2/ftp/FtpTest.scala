package ray.fs2.ftp

import ray.fs2.ftp.settings.FtpCredentials.credentials
import ray.fs2.ftp.settings.FtpSettings

class FtpTest extends BaseFtpTest {
  override val settings = FtpSettings("127.0.0.1", port = 2121, credentials("username", "userpass"))
}