// ---------------------------------------------------------------------------
// Basic settings

name := "avsl"

organization := "org.clapper"

version := "1.0.1"

licenses := Seq("BSD" -> url("http://software.clapper.org/avsl/license.html"))

homepage := Some(url("http://software.clapper.org/avsl/"))

description := "A Very Simple Logger"

scalaVersion := "2.10.4"

// ---------------------------------------------------------------------------
// Additional compiler options and plugins

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature")

crossScalaVersions := scalaVersion.value :: "2.11.0" :: Nil

seq(lsSettings :_*)

(LsKeys.tags in LsKeys.lsync) := Seq("logging", "logger", "simple")

(description in LsKeys.lsync) <<= description(d => d)

// ---------------------------------------------------------------------------
// Additional repositories

resolvers ++= Seq(
    "Java.net Maven 2 Repo" at "http://download.java.net/maven/2",
    "bintray" at "http://dl.bintray.com/bmc/maven"
)

// ---------------------------------------------------------------------------
// Other dependendencies

libraryDependencies ++= Seq(
    "org.clapper" %% "grizzled-scala" % "1.2",
    "javax.mail" % "mail" % "1.4.3",
    "org.slf4j" % "slf4j-api" % "1.7.1",
    "org.scalatest" %% "scalatest" % "2.1.6"
)

// ---------------------------------------------------------------------------
// Publishing criteria

publishTo <<= version { v: String =>
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

pomExtra := (
  <scm>
    <url>git@github.com:bmc/avsl.git/</url>
    <connection>scm:git:git@github.com:bmc/avsl.git</connection>
  </scm>
  <developers>
    <developer>
      <id>bmc</id>
      <name>Brian Clapper</name>
      <url>http://www.clapper.org/bmc</url>
    </developer>
  </developers>
)
