package ray.fs2.ftp

import java.io.FileNotFoundException
import java.nio.file.{ Files, Paths }
import java.util.concurrent.Executors

import cats.effect.{ Blocker, ContextShift, IO, Resource }
import org.scalatest.{ Matchers, WordSpec }
import ray.fs2.ftp.Ftp._
import ray.fs2.ftp.FtpSettings.{ FtpCredentials, UnsecureFtpSettings }

import scala.concurrent.ExecutionContext
import scala.io.Source

trait BaseFtpTest extends WordSpec with Matchers {
  implicit private val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))
  implicit private val cs: ContextShift[IO] = IO.contextShift(ec)

  val settings: UnsecureFtpSettings

  val home = Paths.get("ftp-home/ftp/home")

  "invalid credentials" in {
    connect(settings.copy(credentials = FtpCredentials("test", "test")))
      .use(_ => IO.unit)
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }

  "valid credentials" in {
    connect(settings).use(_.execute(_.isConnected)).unsafeRunSync() shouldBe true
  }

  "listFiles" in {
    connect(settings)
      .use {
        _.lsDescendant("/").compile.toList
      }
      .unsafeRunSync()
      .map(_.path) should contain allElementsOf List("notes.txt", "/dir1/console.dump", "/dir1/users.csv")
  }

  "listFiles with wrong directory" in {
    connect(settings)
      .use {
        _.lsDescendant("/wrong-directory").compile.toList
      }
      .unsafeRunSync() shouldBe Nil
  }

  "stat file" in {
    connect(settings)
      .use {
        _.stat("/dir1/users.csv")
      }
      .unsafeRunSync()
      .map(_.path) shouldBe Some("/dir1/users.csv")
  }

  "stat file does not exist" in {
    connect(settings)
      .use(
        _.stat("/wrong-path.xml")
      )
      .unsafeRunSync()
      .map(_.path) shouldBe None
  }

  "readFile" in {
    val tmp = Files.createTempFile("notes.txt", ".tmp")

    connect(settings)
      .use(
        _.readFile("/notes.txt").through(fs2.io.file.writeAll(tmp, Blocker.liftExecutionContext(ec))).compile.drain
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

    connect(settings)
      .use {
        _.readFile("/no-file.xml")
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
    connect(settings)
      .use(
        _.mkdir("/new-dir")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Right(_) =>
    }

    Files.delete(home.resolve("new-dir"))
  }

  "mkdir fail when invalid path" in {
    connect(settings)
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
    Files.createFile(path)

    connect(settings)
      .use(
        _.rm("/to-delete.txt")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Right(_) =>
    }

    Files.exists(path) shouldBe false
  }

  "rm fail when invalid path" in {
    connect(settings)
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
    Files.createDirectory(path)

    connect(settings)
      .use(
        _.rmdir("/dir-to-delete")
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Right(_) =>
    }

    Files.exists(path) shouldBe false
  }

  "rm fail invalid directory" in {
    connect(settings)
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

    connect(settings)
      .use(
        _.upload("/hello-world.txt", data)
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
    val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary

    connect(settings)
      .use(
        _.upload("/dont-exist/hello-world.txt", data)
      )
      .attempt
      .unsafeRunSync() should matchPattern {
      case Left(_) =>
    }
  }
}
