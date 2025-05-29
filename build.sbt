ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.6.1"
ThisBuild / fork := true
ThisBuild / javaHome := Some(file("C:/Program Files/Java/jdk-24"))
run / connectInput := true

// JVM options
javaOptions ++= Seq(
    "-Xmx32g",
    "--add-modules=jdk.incubator.vector"
)

lazy val root = (project in file("."))
  .settings(
      name := "extended_model",
      scalacOptions ++= Seq(
          "-Yimports:java.lang,scala,scala.Predef,scala.util.chaining,jdk.incubator.vector"
      ),
      run / fork := true,
      compile / javacOptions ++= Seq("--add-modules", "jdk.incubator.vector")
  )

resolvers += "Akka library repository".at("https://repo.akka.io/maven")
val AkkaVersion = "2.7.0" // 2.10.0
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-remote" % AkkaVersion
libraryDependencies += "io.spray" %% "spray-json" % "1.3.6"
libraryDependencies += "org.postgresql" % "postgresql" % "42.7.4"
libraryDependencies += "com.zaxxer" % "HikariCP" % "5.1.0"
libraryDependencies += "tech.ant8e" %% "uuid4cats-effect" % "0.5.0"
libraryDependencies += "com.typesafe.play" %% "play-json" % "2.10.5"

// Akka web
val AkkaHttpVersion = "10.5.3"
libraryDependencies += "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % AkkaVersion

// Logging
libraryDependencies ++= Seq(
    "org.apache.logging.log4j" % "log4j-api" % "2.24.1",
    "org.apache.logging.log4j" % "log4j-core" % "2.24.1",
    "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.24.1",
    "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion
)
