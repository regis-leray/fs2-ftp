package fs2.ftp

import cats.effect.unsafe.IORuntime
import cats.effect.{ IO, Resource }
import fs2.ftp.FtpSettings.{
  KeyCredentials,
  KeyFileSftpIdentity,
  PasswordCredentials,
  RawKeySftpIdentity,
  SecureFtpSettings
}
import fs2.io.file.Files
import net.schmizz.sshj.sftp.Response.StatusCode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.nio.file.{ Paths, Files => JFiles }
import net.schmizz.sshj.sftp.{ SFTPException, SFTPClient => JSFTPClient }
import scala.io.Source

class SecureFtpSpec extends AnyWordSpec with Matchers {
  implicit val runtime: IORuntime = IORuntime.global

  private val settings = SecureFtpSettings("127.0.0.1", port = 2222, PasswordCredentials("foo", "foo"))

  val home = Paths.get("ftp-home/sftp/home/foo")

  "SFtp" should {
    "connect with invalid credentials" in {
      connect[IO, JSFTPClient](settings.copy(credentials = PasswordCredentials("invalid", "invalid")))
        .use(_ => IO.unit)
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "connect with valid credentials" in {
      connect[IO, JSFTPClient](settings).use(_ => IO.unit).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }
    }

    "connect with ssh key file " in {
      val privatekey = io.Source.fromURI(this.getClass.getResource("/ssh_host_rsa_key").toURI).mkString

      val settings = SecureFtpSettings("127.0.0.1", 3333, KeyCredentials("fooz", RawKeySftpIdentity(privatekey)))

      connect[IO, JSFTPClient](settings).use(_ => IO.unit).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }
    }

    "connect with ssh key without passphrase" in {
      val privatekey = Paths.get(this.getClass.getResource("/ssh_host_rsa_key").toURI)

      val settings =
        SecureFtpSettings("127.0.0.1", 3333, KeyCredentials("fooz", KeyFileSftpIdentity(privatekey, None)))

      connect[IO, JSFTPClient](settings).use(_ => IO.unit).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }
    }

    "lsDescendant" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.lsDescendant("/").compile.toList
        )
        .unsafeRunSync()
        .map(_.path) should contain allElementsOf List("/notes.txt", "/dir1/console.dump", "/dir1/users.csv")
    }

    "lsDescendant with wrong directory" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.lsDescendant("wrong-directory").compile.toList
        )
        .unsafeRunSync() shouldBe Nil
    }

    "ls" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.ls("/").compile.toList
        )
        .unsafeRunSync()
        .map(_.path) should contain allElementsOf List("/notes.txt", "/dir1")
    }

    "ls with wrong directory" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.ls("wrong-directory").compile.toList
        )
        .unsafeRunSync() shouldBe Nil
    }

    "stat file" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.stat("/dir1/users.csv")
        )
        .unsafeRunSync()
        .map(_.path) shouldBe Some("/dir1/users.csv")
    }

    "stat file does not exist" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.stat("/wrong-path.xml")
        )
        .unsafeRunSync()
        .map(_.path) shouldBe None
    }

    "readFile" in {
      val tmp = JFiles.createTempFile("notes.txt", ".tmp")

      connect[IO, JSFTPClient](settings)
        .use(
          _.readFile("/notes.txt")
            .through(Files[IO].writeAll(fs2.io.file.Path.fromNioPath(tmp)))
            .compile
            .drain
        )
        .unsafeRunSync()

      Resource
        .make(IO(Source.fromFile(tmp.toFile)))(s => IO(s.close()))
        .use(s => IO(s.mkString))
        .unsafeRunSync() shouldBe
        """|Hello world !!!
           |this is a beautiful day""".stripMargin
    }

    "readFile does not exist" in {
      val tmp = JFiles.createTempFile("notes.txt", ".tmp")

      connect[IO, JSFTPClient](settings)
        .use {
          _.readFile("/no-file.xml")
            .through(Files[IO].writeAll(fs2.io.file.Path.fromNioPath(tmp)))
            .compile
            .drain
        }
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(ex: SFTPException) if ex.getStatusCode == StatusCode.NO_SUCH_FILE =>
      }
    }

    "mkdirs directory" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.mkdir("/dir1/new-dir")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      JFiles.delete(home.resolve("dir1/new-dir"))
    }

    "mkdirs fail when invalid path" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.mkdir("/dir1/users.csv")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(_: SFTPException) =>
      }
    }

    "rm valid path" in {
      val path = home.resolve("dir1/to-delete.txt")
      JFiles.createFile(path)

      connect[IO, JSFTPClient](settings)
        .use(
          _.rm("/dir1/to-delete.txt")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      JFiles.exists(path) shouldBe false
    }

    "rm fail when invalid path" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.rm("upload/dont-exist")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(ex: SFTPException) if ex.getStatusCode == StatusCode.NO_SUCH_FILE =>
      }
    }

    "rmdir directory" in {
      val path = home.resolve("dir1/dir-to-delete")

      JFiles.createDirectory(path)

      connect[IO, JSFTPClient](settings)
        .use(
          _.rmdir("/dir1/dir-to-delete")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      JFiles.exists(path) shouldBe false
    }

    "rmdir fail invalid directory" in {
      connect[IO, JSFTPClient](settings)
        .use(
          _.rmdir("/dont-exist")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(ex: SFTPException) if ex.getStatusCode == StatusCode.NO_SUCH_FILE =>
      }
    }

    "upload a file" in {
      val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary
      val path                       = home.resolve("dir1/hello-world.txt")

      connect[IO, JSFTPClient](settings)
        .use(c => data.through(c.upload("/dir1/hello-world.txt")).compile.drain)
        .attempt
        .unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      Resource
        .make(IO(Source.fromFile(path.toFile)))(s => IO(s.close()))
        .use(s => IO(s.mkString))
        .unsafeRunSync() shouldBe "Hello F World"

      JFiles.delete(path)
    }

    "upload fail when path is invalid" in {
      val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary

      connect[IO, JSFTPClient](settings)
        .use(c => data.through(c.upload("/dont-exist/hello-world.txt")).compile.drain)
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(ex: SFTPException) if ex.getStatusCode == StatusCode.NO_SUCH_FILE =>
      }
    }

    "connect and disconnect multiple times in a row" in {
      val ls = connect[IO, JSFTPClient](settings)
        .use(
          _.ls("/").compile.toList
        )

      fs2.Stream
        .repeatEval(ls)
        .take(2)
        .compile
        .toList
        .unsafeRunSync()
        .flatten
        .map(_.path) should contain allElementsOf List("/notes.txt", "/dir1")
    }
  }
}
