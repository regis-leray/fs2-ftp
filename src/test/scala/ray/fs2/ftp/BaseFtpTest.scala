package ray.fs2.ftp

import java.io.FileNotFoundException
import java.nio.file.{ Files, Paths }
import java.util.concurrent.Executors

import cats.effect.{ Blocker, ContextShift, IO, Resource }
import org.scalatest.{ Matchers, WordSpec }
import ray.fs2.ftp.Ftp._

import scala.concurrent.ExecutionContext
import scala.io.Source

trait BaseFtpTest extends WordSpec with Matchers {
  implicit private val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))
  implicit private val cs: ContextShift[IO] = IO.contextShift(ec)

  val settings: FtpSettings

  val home = Paths.get("ftp-home/ftp/home")

  "invalid credentials" in {
    connect[IO](settings.copy(credentials = FtpCredentials("test", "test")))
      .use(_ => IO.unit)
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

  "valid credentials" in {
    connect[IO](settings).use(c => IO.pure(c.isConnected)).unsafeRunSync() shouldBe true
  }

  "listFiles" in {
    connect[IO](settings)
      .use {
        lsDescendant[IO]("/")(_).compile.toList
      }
      .unsafeRunSync()
      .map(_.path) should contain allElementsOf List("notes.txt", "console.dump", "users.csv")
  }

  "listFiles with wrong directory" in {
    connect[IO](settings)
      .use {
        lsDescendant[IO]("/wrong-directory")(_).compile.toList
      }
      .unsafeRunSync() shouldBe Nil
  }

  "stat file" in {
    connect[IO](settings)
      .use {
        stat[IO]("/dir1/users.csv")
      }
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
        readFile[IO]("/notes.txt")(_).through(fs2.io.file.writeAll(tmp, Blocker.liftExecutionContext(ec))).compile.drain
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
      case Left(_: FileNotFoundException) =>
    }
  }
  "mkdir directory" in {
    connect[IO](settings)
      .use(
        mkdir[IO]("/new-dir")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Right(_) =>
    }

    Files.delete(home.resolve("new-dir"))
  }

  "mkdir fail when invalid path" in {
    connect[IO](settings)
      .use(
        mkdir[IO]("/dir1/users.csv")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

  "rm valid path" in {
    val path = home.resolve("to-delete.txt")
    Files.createFile(path)

    connect[IO](settings)
      .use(
        rm[IO]("/to-delete.txt")
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
        rm[IO]("/dont-exist")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

  "rm directory" in {
    val path = home.resolve("dir-to-delete")
    Files.createDirectory(path)

    connect[IO](settings)
      .use(
        rmdir[IO]("/dir-to-delete")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Right(_) =>
    }

    Files.exists(path) shouldBe false
  }

  "rm fail invalid directory" in {
    connect[IO](settings)
      .use(
        rmdir[IO]("/dont-exist")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

  "upload a file" in {
    val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary[IO]
    val path                       = home.resolve("hello-world.txt")

    connect[IO](settings)
      .use(
        upload[IO]("/hello-world.txt", data)
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
        upload[IO]("/dont-exist/hello-world.txt", data)
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }
}
