lazy val scala212 = "2.12.15"
lazy val scala213 = "2.13.7"
lazy val scala310 = "3.1.2"

val fs2Version = "3.2.9"

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
ThisBuild / scalaVersion := scala310
ThisBuild / crossScalaVersions := List(scala310, scala213, scala212)
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.graalvm("20.3.1", "11"))
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    List(
      "chmod -R 777 ./ftp-home/",
      "docker-compose -f \"docker-compose.yml\" up -d --build",
      "chmod -R 777 ./ftp-home/sftp/home/foo/dir1"
    ),
    name = Some("Start containers")
  )
)
ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("check", "test"))
)

//sbt-ci-release settings
ThisBuild / githubWorkflowPublishPreamble := Seq(
  WorkflowStep.Use(UseRef.Public("olafurpg", "setup-gpg", "v3"))
)

ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq(
  RefPredicate.StartsWith(Ref.Branch("master")),
  RefPredicate.StartsWith(Ref.Tag("v"))
)
ThisBuild / githubWorkflowPublish := Seq(WorkflowStep.Sbt(List("ci-release")))
ThisBuild / githubWorkflowEnv ++= List(
  "PGP_PASSPHRASE",
  "PGP_SECRET",
  "SONATYPE_PASSWORD",
  "SONATYPE_USERNAME"
).map(envKey => envKey -> s"$${{ secrets.$envKey }}").toMap

lazy val `fs2-ftp` = project
  .in(file("."))
  .settings(
    name := "fs2-ftp",
    Test / fork := true,
    Test / parallelExecution := false,
    publishMavenStyle := true,
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
      "org.scala-lang.modules"   %% "scala-collection-compat" % "2.7.0",
      "com.hierynomus"           % "sshj"                     % "0.33.0",
      "commons-net"              % "commons-net"              % "3.8.0",
      "org.apache.logging.log4j" % "log4j-api"                % "2.17.2" % Test,
      "org.apache.logging.log4j" % "log4j-core"               % "2.17.2" % Test,
      "org.apache.logging.log4j" % "log4j-slf4j-impl"         % "2.17.2" % Test,
      "org.scalatest"            %% "scalatest"               % "3.2.12" % Test
    )
  )

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
