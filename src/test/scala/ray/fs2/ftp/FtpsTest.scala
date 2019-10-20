package ray.fs2.ftp

import ray.fs2.ftp.settings.FtpCredentials.credentials
import ray.fs2.ftp.settings.{FtpSettings, FtpsSettings}

class FtpsTest extends BaseFtpTest {
  override val settings: FtpSettings = FtpsSettings("127.0.0.1", 2121, credentials("username", "userpass"))
}