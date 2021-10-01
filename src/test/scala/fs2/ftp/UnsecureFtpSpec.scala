package fs2.ftp

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import fs2.ftp.FtpSettings.{FtpCredentials, UnsecureFtpSettings}
import fs2.io.file.Files
import org.apache.commons.net.ftp.{FTPClient => JFTPClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.FileNotFoundException
import java.nio.file.{Paths, Files => JFiles}
import scala.io.Source

trait BaseFtpTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  implicit val runtime: IORuntime = IORuntime.global

  val settings: UnsecureFtpSettings

  val home = Paths.get("ftp-home/ftp/home")

  "invalid credentials" in {
    connect[IO, JFTPClient](settings.copy(credentials = FtpCredentials("test", "test")))
      .use(_ => IO.unit)
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

  "valid credentials" in {
    connect[IO, JFTPClient](settings).use(_.execute(_.isConnected)).unsafeRunSync() shouldBe true
  }

  "ls" in {
    connect[IO, JFTPClient](settings)
      .use {
        _.ls("/").compile.toList
      }
      .unsafeRunSync()
      .map(_.path) should contain allElementsOf List("/notes.txt", "/dir1")
  }

  "ls with wrong dir" in {
    connect[IO, JFTPClient](settings)
      .use {
        _.ls("/wrong-dir").compile.toList
      }
      .unsafeRunSync()
      .map(_.path) should contain allElementsOf Nil
  }

  "lsDescendant" in {
    connect[IO, JFTPClient](settings)
      .use {
        _.lsDescendant("/").compile.toList
      }
      .unsafeRunSync()
      .map(_.path) should contain allElementsOf List("/notes.txt", "/dir1/console.dump", "/dir1/users.csv")
  }

  "lsDescendant with wrong directory" in {
    connect[IO, JFTPClient](settings)
      .use {
        _.lsDescendant("/wrong-directory").compile.toList
      }
      .unsafeRunSync() shouldBe Nil
  }

  "stat file" in {
    connect[IO, JFTPClient](settings)
      .use {
        _.stat("/dir1/users.csv")
      }
      .unsafeRunSync()
      .map(_.path) shouldBe Some("/dir1/users.csv")
  }

  "stat file does not exist" in {
    connect[IO, JFTPClient](settings)
      .use(
        _.stat("/wrong-path.xml")
      )
      .unsafeRunSync()
      .map(_.path) shouldBe None
  }

  "readFile" in {
    val tmp = JFiles.createTempFile("notes.txt", ".tmp")

    connect[IO, JFTPClient](settings)
      .use(
        _.readFile("/notes.txt").through(Files[IO].writeAll(tmp)).compile.drain
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

    connect[IO, JFTPClient](settings)
      .use {
        _.readFile("/no-file.xml")
          .through(Files[IO].writeAll(tmp))
          .compile
          .drain
      }
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_: FileNotFoundException) =>
    }
  }
  "mkdir directory" in {
    connect[IO, JFTPClient](settings)
      .use(
        _.mkdir("/new-dir")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Right(_) =>
    }

    JFiles.delete(home.resolve("new-dir"))
  }

  "mkdir fail when invalid path" in {
    connect[IO, JFTPClient](settings)
      .use(
        _.mkdir("/dir1/users.csv")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

  "rm valid path" in {
    val path = home.resolve("to-delete.txt")
    JFiles.createFile(path)

    connect[IO, JFTPClient](settings)
      .use(
        _.rm("/to-delete.txt")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Right(_) =>
    }

    JFiles.exists(path) shouldBe false
  }

  "rm fail when invalid path" in {
    connect[IO, JFTPClient](settings)
      .use(
        _.rm("/dont-exist")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

  "rm directory" in {
    val path = home.resolve("dir-to-delete")


    JFiles.createDirectory(path)

    connect[IO, JFTPClient](settings)
      .use(
        _.rmdir("/dir-to-delete")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Right(_) =>
    }

    JFiles.exists(path) shouldBe false
  }

  "rm fail invalid directory" in {
    connect[IO, JFTPClient](settings)
      .use(
        _.rmdir("/dont-exist")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

  "upload a file" in {
    val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary
    val path                       = home.resolve("hello-world.txt")

    connect[IO, JFTPClient](settings)
      .use(c => data.through(c.upload("/hello-world.txt")).compile.drain)
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

    connect[IO, JFTPClient](settings)
      .use(c => data.through(c.upload("/dont-exist/hello-world.txt")).compile.drain)
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

}

class UnsecureFtpSslTest extends BaseFtpTest {

  override val settings: UnsecureFtpSettings =
    UnsecureFtpSettings.ssl("127.0.0.1", 2121, FtpCredentials("username", "userpass"))
}

class UnsecureFtpTest extends BaseFtpTest {
  override val settings = UnsecureFtpSettings("127.0.0.1", port = 2121, FtpCredentials("username", "userpass"))
}
