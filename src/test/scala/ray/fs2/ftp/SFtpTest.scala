package ray.fs2.ftp

import java.nio.file.{Files, Paths}

import cats.effect.Resource.fromAutoCloseable
import cats.effect.{ContextShift, IO}
import net.schmizz.sshj.{DefaultConfig, SSHClient}
import org.scalatest.{Matchers, WordSpec}
import ray.fs2.ftp.SFtp._
import ray.fs2.ftp.settings.FtpCredentials.credentials
import ray.fs2.ftp.settings.SFtpSettings

import scala.concurrent.ExecutionContext
import scala.io.Source

class SFtpTest extends WordSpec with Matchers {

  implicit private val ec: ExecutionContext = ExecutionContext.global
  implicit private val cs: ContextShift[IO] = IO.contextShift(ec)

  private val settings = SFtpSettings("localhost", port = 2222,  credentials("foo", "foo"))

  val home = Paths.get("src","test","resources", "sftp", "home", "foo")

  "SFtp" should {
    "listFiles" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      (for {
        client <- connect[IO](settings)
        files <- listFiles[IO](client)("/").compile.toList
        _ <- disconnect[IO](client)
      } yield files)
        .unsafeRunSync().map(_.name) should contain allElementsOf List("notes.txt", "console.dump", "users.csv")
    }

    "listFiles with wrong directory" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      (for {
        client <- connect[IO](settings)
        files <- listFiles[IO](client)("wrong-directory").compile.toList
          _ <- disconnect[IO](client)
      } yield files).unsafeRunSync() shouldBe Nil
    }

    "stat file" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      (for {
        client <- connect[IO](settings)
        file <- stat[IO](client)("/dir1/users.csv")
        _ <- disconnect[IO](client)
      } yield file).unsafeRunSync().map(_.name) shouldBe Some("users.csv")
    }

    "stat file does not exist" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      (for {
        client <- connect[IO](settings)
        file <- stat[IO](client)("/wrong-path.xml")
        _ <- disconnect[IO](client)
      } yield file).unsafeRunSync().map(_.name) shouldBe None
    }

    "readFile" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      val tmp = Files.createTempFile("notes.txt", ".tmp")

      (for {
        client <- connect[IO](settings)
        _ <- readFile[IO](client)("/notes.txt").through(fs2.io.file.writeAll(tmp, ec)).compile.drain
        _ <- disconnect[IO](client)
      } yield tmp).map(Files.size).unsafeRunSync() shouldBe >(0L)

      fromAutoCloseable(IO(Source.fromFile(tmp.toFile)))
        .use(s => IO(s.mkString)).unsafeRunSync() shouldBe
       """|Hello world !!!
          |this is a beautiful day""".stripMargin
    }

    "readFile does not exist" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

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

    "mkdirs directory" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      (for {
        client <- connect[IO](settings)
        _ <- mkdirs[IO](client)("/new-dir/ndir")
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      List("new-dir/ndir", "new-dir").foreach(p => Files.delete(home.resolve(p)))
    }

    "mkdirs fail when invalid path" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      (for {
        client <- connect[IO](settings)
        _ <- mkdirs[IO](client)("/dir1/users.csv")
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "rm valid path" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

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
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      (for {
        client <- connect[IO](settings)
        _ <- rm[IO](client)("upload/dont-exist")
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "rm directory" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)
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
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      (for {
        client <- connect[IO](settings)
        _ <- rmdir[IO](client)("/dont-exist")
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }

    "upload a file" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)

      val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary[IO]
      val path = home.resolve("hello-world.txt")

      (for {
        client <- connect[IO](settings)
        _ <- upload[IO](client)("/hello-world.txt", data)
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Right(_) =>
      }

      fromAutoCloseable(IO(Source.fromFile(path.toFile)))
        .use(s => IO(s.mkString)).unsafeRunSync() shouldBe "Hello F World"

      Files.delete(path)
    }

    "upload fail when path is invalid" in {
      implicit val sshClient: SSHClient = new SSHClient(new DefaultConfig)
      val data: fs2.Stream[IO, Byte] = fs2.Stream.emits("Hello F World".getBytes.toSeq).covary[IO]

      (for {
        client <- connect[IO](settings)
        _ <- upload[IO](client)("/dont-exist/hello-world.txt", data)
        _ <- disconnect[IO](client)
      } yield ()).attempt.unsafeRunSync() should matchPattern {
        case Left(_) =>
      }
    }
  }
}
