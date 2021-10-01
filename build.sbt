lazy val scala212 = "2.12.13"
lazy val scala213 = "2.13.5"
val GraalVM11 = "graalvm-ce-java11@20.3.0"

ThisBuild / scalaVersion := scala213
ThisBuild / crossScalaVersions := List(scala213, scala212)

ThisBuild / githubWorkflowJavaVersions := Seq(GraalVM11)

//sbt-ci-release settings
ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(List(
    "chmod -R 777 ./ftp-home/",
    "docker-compose -f \"docker-compose.yml\" up -d --build",
    "chmod -R 777 ./ftp-home/sftp/home/foo/dir1"
  ), name = Some("Start containers"))
)

ThisBuild / githubWorkflowPublishPreamble := Seq(

)

ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v")))
ThisBuild / githubWorkflowPublish := Seq(WorkflowStep.Sbt(List("ci-release")))
ThisBuild / githubWorkflowEnv ++= List(
  "PGP_PASSPHRASE",
  "PGP_SECRET",
  "SONATYPE_PASSWORD",
  "SONATYPE_USERNAME"
).map { envKey =>
  envKey -> s"$${{ secrets.$envKey }}"
}.toMap


lazy val `fs2-ftp` = project
  .in(file("."))
  .settings(
    organization := "com.github.regis-leray",
    name := "fs2-ftp",
    description := "fs2-ftp",
    Test / fork := true,
    Test / parallelExecution := false,
    homepage := Some(url("https://github.com/regis-leray/fs2-ftp")),
    scmInfo := Some(ScmInfo(url("https://github.com/regis-leray/fs2-ftp"), "git@github.com:regis-leray/fs2-ftp.git")),
    developers := List(
      Developer("username", "Regis Leray", "regis.leray at gmail dot com", url("https://github.com/regis-leray"))
    ),
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    publishMavenStyle := true,
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
    //PgpKeys.pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray()),
    publishTo := sonatypePublishToBundle.value,
  )
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2"                   %% "fs2-core"                % "3.0.6",
      "co.fs2"                   %% "fs2-io"                  % "3.0.6",
      "org.scala-lang.modules"   %% "scala-collection-compat" % "2.4.4",
      "com.hierynomus"           % "sshj"                     % "0.31.0",
      "commons-net"              % "commons-net"              % "3.8.0",
      "org.apache.logging.log4j" % "log4j-api"                % "2.13.0" % Test,
      "org.apache.logging.log4j" % "log4j-core"               % "2.13.0" % Test,
      "org.apache.logging.log4j" % "log4j-slf4j-impl"         % "2.13.0" % Test,
      "org.scalatest"            %% "scalatest"               % "3.2.3" % Test
    )
  )

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
