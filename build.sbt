lazy val scala213 = "2.13.1"
lazy val scala212 = "2.12.11"

lazy val `fs2-ftp` = project
  .in(file("."))
  .settings(
    organization := "com.github.regis-leray",
    name := "fs2-ftp",
    description := "fs2-ftp",
    Test / fork := true,
    parallelExecution in Test := false,
    homepage := Some(url("https://github.com/regis-leray/fs2-ftp")),
    scmInfo := Some(ScmInfo(url("https://github.com/regis-leray/fs2-ftp"), "git@github.com:regis-leray/fs2-ftp.git")),
    developers := List(
      Developer("username", "Regis Leray", "regis.leray at gmail dot com", url("https://github.com/regis-leray"))
    ),
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    publishMavenStyle := true,
    crossScalaVersions := List(scala213, scala212),
    scalaVersion := scala213,
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xlint",
      "-Xfatal-warnings",
      "-language:higherKinds",
      "-language:postfixOps"
    ) ++ PartialFunction
      .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
        case Some((2, n)) if n < 13 => Seq("-Ypartial-unification")
      }
      .toList
      .flatten,
    PgpKeys.pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray()),
    publishTo := sonatypePublishToBundle.value
  )
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2"                   %% "fs2-core"                % "2.4.4",
      "co.fs2"                   %% "fs2-io"                  % "2.4.4",
      "org.scala-lang.modules"   %% "scala-collection-compat" % "2.1.6",
      "com.hierynomus"           % "sshj"                     % "0.30.0",
      "commons-net"              % "commons-net"              % "3.6",
      "org.apache.logging.log4j" % "log4j-api"                % "2.13.0" % Test,
      "org.apache.logging.log4j" % "log4j-core"               % "2.13.0" % Test,
      "org.apache.logging.log4j" % "log4j-slf4j-impl"         % "2.13.0" % Test,
      "org.scalatest"            %% "scalatest"               % "3.2.1" % Test
    )
  )

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
