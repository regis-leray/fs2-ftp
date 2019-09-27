
// Release
import ReleaseTransformations._

lazy val scala212 = "2.12.10"
lazy val scala211 = "2.11.12"

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
    developers := List(Developer("username", "Regis Leray", "regis.leray at gmail dot com", url("https://github.com/regis-leray"))),
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),

    publishMavenStyle := true,
    // crossScalaVersions must be set to Nil on the aggregating project
    crossScalaVersions := List(scala212, scala211),
    scalaVersion := scala212,

    scalacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xlint", //"-Xfatal-warnings",
      "-language:higherKinds",
      "-Ypartial-unification",
      "-language:postfixOps"
    ),

    credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credential"),

    publishTo := {
      if (isSnapshot.value)
        Some(Opts.resolver.sonatypeSnapshots)
      else
        Some(Opts.resolver.sonatypeStaging)
    },

    releasePublishArtifactsAction := PgpKeys.publishSigned.value,

    // don't use sbt-release's cross facility
    releaseCrossBuild := false,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      releaseStepCommandAndRemaining("+compile"),
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      releaseStepCommandAndRemaining("+publishSigned"),
      setNextVersion,
      commitNextVersion,
      releaseStepCommand("+sonatypeReleaseAll"),
      pushChanges
    ),
  )
  .settings(libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % "1.0.5", // For cats 1.5.0 and cats-effect 1.2.0
    "co.fs2" %% "fs2-io" % "1.0.5",
    "com.hierynomus" % "sshj" % "0.27.0",
    "commons-net" % "commons-net" % "3.6",

    "org.apache.logging.log4j" % "log4j-api" % "2.12.0" % Test,
    "org.apache.logging.log4j" % "log4j-core" % "2.12.0" % Test,
    "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.12.0" % Test,
    "org.scalatest" %% "scalatest" % "3.0.8" % Test
  ))
