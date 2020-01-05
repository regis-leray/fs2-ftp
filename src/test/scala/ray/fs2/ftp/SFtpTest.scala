package ray.fs2.ftp

import java.nio.file.{ Files, Paths }

import cats.effect.{ Blocker, ContextShift, IO, Resource }
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPException
import org.scalatest.{ Matchers, WordSpec }
import ray.fs2.ftp.SFtp._

import scala.concurrent.ExecutionContext
import scala.io.Source

class SFtpTest extends WordSpec with Matchers {
  implicit private val ec: ExecutionContext = ExecutionContext.global
  implicit private val cs: ContextShift[IO] = IO.contextShift(ec)

  private val settings = SFtpSettings("127.0.0.1", port = 2222, FtpCredentials("foo", "foo"))

  val home = Paths.get("ftp-home/sftp/home/foo")

  "SFtp" should {
    "connect with invalid credentials" in {
      connect[IO](settings.copy(credentials = FtpCredentials("invalid", "invalid")))
        .use(_ => IO.unit)
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "connect with valid credentials" in {
      connect[IO](settings).use(_ => IO.unit).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }
    }

    "listFiles" in {
      connect[IO](settings)
        .use(
          lsDescendant[IO]("/")(_).compile.toList
        )
        .unsafeRunSync()
        .map(_.path) should contain allElementsOf List("notes.txt", "console.dump", "users.csv")
    }

    "listFiles with wrong directory" in {
      connect[IO](settings)
        .use(
          lsDescendant[IO]("wrong-directory")(_).compile.toList
        )
        .unsafeRunSync() shouldBe Nil
    }

    "stat file" in {
      connect[IO](settings)
        .use(
          stat[IO]("/dir1/users.csv")
        )
        .unsafeRunSync()
        .map(_.path) shouldBe Some("users.csv")
    }

    "stat file does not exist" in {
      connect[IO](settings)
        .use(
          stat[IO]("/wrong-path.xml")
        )
        .unsafeRunSync()
        .map(_.path) shouldBe None
    }

    "readFile" in {
      val tmp = Files.createTempFile("notes.txt", ".tmp")

      connect[IO](settings)
        .use(
          readFile[IO]("/notes.txt")(_)
            .through(fs2.io.file.writeAll(tmp, Blocker.liftExecutionContext(ec)))
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
      val tmp = Files.createTempFile("notes.txt", ".tmp")

      connect[IO](settings)
        .use {
          readFile[IO]("/no-file.xml")(_)
            .through(fs2.io.file.writeAll(tmp, Blocker.liftExecutionContext(ec)))
            .compile
            .drain
        }
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(ex: SFTPException) if ex.getStatusCode == StatusCode.NO_SUCH_FILE =>
      }
    }

    "mkdirs directory" in {
      connect[IO](settings)
        .use(
          mkdirs[IO]("/dir1/new-dir")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      Files.delete(home.resolve("dir1/new-dir"))
    }

    "mkdirs fail when invalid path" in {
      connect[IO](settings)
        .use(
          mkdirs[IO]("/dir1/users.csv")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(_: SFTPException) =>
      }
    }

    "rm valid path" in {
      val path = home.resolve("dir1/to-delete.txt")
      Files.createFile(path)

      connect[IO](settings)
        .use(
          rm[IO]("/dir1/to-delete.txt")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      Files.exists(path) shouldBe false
    }

    "rm fail when invalid path" in {
      connect[IO](settings)
        .use(
          rm[IO]("upload/dont-exist")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(ex: SFTPException) if ex.getStatusCode == StatusCode.NO_SUCH_FILE =>
      }
    }

    "rmdir directory" in {
      val path = home.resolve("dir1/dir-to-delete")

      Files.createDirectory(path)

      connect[IO](settings)
        .use(
          rmdir[IO]("/dir1/dir-to-delete")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      Files.exists(path) shouldBe false
    }

    "rmdir fail invalid directory" in {
      connect[IO](settings)
        .use(
          rmdir[IO]("/dont-exist")
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(ex: SFTPException) if ex.getStatusCode == StatusCode.NO_SUCH_FILE =>
      }
    }

    "upload a file" in {
      val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary[IO]
      val path                       = home.resolve("dir1/hello-world.txt")

      connect[IO](settings)
        .use(
          upload("/dir1/hello-world.txt", data)
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      Resource
        .make(IO(Source.fromFile(path.toFile)))(s => IO(s.close()))
        .use(s => IO(s.mkString))
        .unsafeRunSync() shouldBe "Hello F World"

      Files.delete(path)
    }

    "upload fail when path is invalid" in {
      val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary[IO]

      connect[IO](settings)
        .use(
          upload("/dont-exist/hello-world.txt", data)
        )
        .attempt
        .unsafeRunSync() should matchPattern {
        case Left(ex: SFTPException) if ex.getStatusCode == StatusCode.NO_SUCH_FILE =>
      }
    }
  }
}
