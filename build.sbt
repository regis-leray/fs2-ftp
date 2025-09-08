lazy val scala212 = "2.12.20"
lazy val scala213 = "2.13.16"
lazy val scala330 = "3.3.4"

val fs2Version = "3.12.2"

inThisBuild(
  List(
    organization := "com.github.regis-leray",
    homepage := Some(url("https://github.com/regis-leray/fs2-ftp")),
    scmInfo := Some(ScmInfo(url("https://github.com/regis-leray/fs2-ftp"), "git@github.com:regis-leray/fs2-ftp.git")),
    developers := List(
      Developer("regis_leray", "Regis Leray", "regis.leray at gmail dot com", url("https://github.com/regis-leray"))
    ),
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
  )
)
ThisBuild / scalaVersion := scala330
ThisBuild / crossScalaVersions := List(scala330, scala213, scala212)
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.graalvm(Graalvm.Distribution("graalvm"), "17"))
ThisBuild / versionScheme := Some("early-semver")
//https://github.com/sbt/sbt-ci-release/releases/tag/v1.11.0
ThisBuild / sbtPluginPublishLegacyMavenStyle := false

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    List(
      "chmod -R 777 ./ftp-home/",
      "docker compose -f \"docker-compose.yml\" up -d --build",
      "chmod -R 777 ./ftp-home/sftp/home/foo/dir1"
    ),
    name = Some("Start containers")
  )
)

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("check", "test"))
)

ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq(
  RefPredicate.StartsWith(Ref.Branch("master")),
  RefPredicate.StartsWith(Ref.Tag("v"))
)

ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    commands = List("ci-release"),
    name = Some("Publish project"),
    env = Map(
      "PGP_PASSPHRASE"    -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET"        -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  )
)

lazy val `fs2-ftp` = project
  .in(file("."))
  .settings(
    name := "fs2-ftp",
    Test / fork := true,
    Test / parallelExecution := false,
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xfatal-warnings",
      "-language:higherKinds",
      "-language:postfixOps"
    ) ++ PartialFunction
      .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
        case Some((2, n)) if n < 13  => Seq("-Xlint", "-Ypartial-unification")
        case Some((2, n)) if n >= 13 => Seq("-Xlint")
      }
      .toList
      .flatten
  )
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2"                   %% "fs2-core"                % fs2Version,
      "co.fs2"                   %% "fs2-io"                  % fs2Version,
      "org.scala-lang.modules"   %% "scala-collection-compat" % "2.13.0",
      "com.hierynomus"           % "sshj"                     % "0.40.0",
      "commons-net"              % "commons-net"              % "3.12.0",
      "org.apache.logging.log4j" % "log4j-api"                % "2.25.1" % Test,
      "org.apache.logging.log4j" % "log4j-core"               % "2.25.1" % Test,
      "org.apache.logging.log4j" % "log4j-slf4j-impl"         % "2.25.1" % Test,
      "org.scalatest"            %% "scalatest"               % "3.2.19" % Test
    )
  )

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
