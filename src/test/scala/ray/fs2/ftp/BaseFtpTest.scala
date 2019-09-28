package ray.fs2.ftp

import java.nio.file.{Files, Paths}

import cats.effect.{ContextShift, IO, Resource}
import org.scalatest.{Matchers, WordSpec}
import ray.fs2.ftp.Ftp._
import ray.fs2.ftp.settings.FtpCredentials.credentials
import ray.fs2.ftp.settings.FtpSettings

import scala.concurrent.ExecutionContext
import scala.io.Source

trait BaseFtpTest extends WordSpec with Matchers {

  implicit private val ec: ExecutionContext = ExecutionContext.global
  implicit private val cs: ContextShift[IO] = IO.contextShift(ec)

  val settings: FtpSettings

  val home = Paths.get("ftp-home/ftp/home")

  "Ftp" should {
    "invalid credentials" in {
      connect[IO](settings.copy(credentials = credentials("test", "test")))
        .attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "valid credentials" in {
      val attempt = connect[IO](settings).attempt.unsafeRunSync()

      attempt should matchPattern {
        case Right(_) =>
      }

      attempt.right.get.logout()
      attempt.right.get.disconnect()
    }

    "listFiles" in {
      (for {
        client <- connect[IO](settings)
        files <- listFiles[IO](client)("/").compile.toList
        _ <- disconnect[IO](client)
      } yield files)
        .unsafeRunSync().map(_.name) should contain allElementsOf List("notes.txt", "console.dump", "users.csv")
    }

    "listFiles with wrong directory" in {
      (for {
        client <- connect[IO](settings)
        files <- listFiles[IO](client)("/wrong-directory").compile.toList
        _ <- disconnect[IO](client)
      } yield files)
        .unsafeRunSync() shouldBe Nil
    }

    "stat file" in {
      (for {
        client <- connect[IO](settings)
        file <- stat[IO](client)("/dir1/users.csv")
        _ <- disconnect[IO](client)
      } yield file).unsafeRunSync().map(_.name) shouldBe Some("users.csv")
    }

    "stat file does not exist" in {
      (for {
        client <- connect[IO](settings)
        file <- stat[IO](client)("/wrong-path.xml")
          .handleErrorWith { t => disconnect[IO](client).flatMap(_ => IO.raiseError(t)) }
      } yield file).unsafeRunSync().map(_.name) shouldBe None
    }

    "readFile" in {
      val tmp = Files.createTempFile("notes.txt", ".tmp")

      (for {
        client <- connect[IO](settings)
        _ <- readFile[IO](client)("/notes.txt").through(fs2.io.file.writeAll(tmp, ec)).compile.drain
        _ <- disconnect[IO](client)
      } yield tmp).map(Files.size).unsafeRunSync() shouldBe >(0L)
    }

    "readFile does not exist" in {
      val tmp = Files.createTempFile("notes.txt", ".tmp")

      (for {
        client <- connect[IO](settings)
        _ <- readFile[IO](client)("/no-file.xml").through(fs2.io.file.writeAll(tmp, ec))
          .compile.drain
          .handleErrorWith { t => disconnect[IO](client).flatMap(_ => IO.raiseError(t)) }
      } yield tmp).attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "mkdir directory" in {
      (for {
        client <- connect[IO](settings)
        _ <- mkdir[IO](client)("/new-dir")
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      Files.delete(home.resolve("new-dir"))
    }

    "mkdir fail when invalid path" in {
      (for {
        client <- connect[IO](settings)
        _ <- mkdir[IO](client)("/dir1/users.csv")
          .handleErrorWith { t => disconnect[IO](client).flatMap(_ => IO.raiseError(t)) }
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "rm valid path" in {
      val path = home.resolve("to-delete.txt")
      Files.createFile(path)

      (for {
        client <- connect[IO](settings)
        _ <- rm[IO](client)("/to-delete.txt")
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      Files.exists(path) shouldBe false
    }

    "rm fail when invalid path" in {
      (for {
        client <- connect[IO](settings)
        _ <- rm[IO](client)("/dont-exist")
          .handleErrorWith { t => disconnect[IO](client).flatMap(_ => IO.raiseError(t)) }
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "rm directory" in {
      val path = home.resolve("dir-to-delete")
      Files.createDirectory(path)

      (for {
        client <- connect[IO](settings)
        _ <- rmdir[IO](client)("/dir-to-delete")
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      Files.exists(path) shouldBe false
    }

    "rm fail invalid directory" in {
      (for {
        client <- connect[IO](settings)
        _ <- rmdir[IO](client)("/dont-exist")
          .handleErrorWith { t => disconnect[IO](client).flatMap(_ => IO.raiseError(t)) }
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "upload a file" in {
      val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary[IO]
      val path = home.resolve("hello-world.txt")

      (for {
        client <- connect[IO](settings)
        _ <- upload[IO](client)("/hello-world.txt", data)
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      Resource.make(IO(Source.fromFile(path.toFile)))(s => IO(s.close()))
        .use(s =>IO(s.mkString)).unsafeRunSync() shouldBe "Hello F World"

      Files.delete(path)
    }

    "upload fail when path is invalid" in {
      val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary[IO]

      (for {
        client <- connect[IO](settings)
        _ <- upload[IO](client)("/dont-exist/hello-world.txt", data)
          .handleErrorWith { t => disconnect[IO](client).flatMap(_ => IO.raiseError(t)) }
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }
  }
}
