ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.4.1"
ThisBuild / fork := true

// Set JVM options, such as increasing the max heap size
ThisBuild / javaOptions += "-Xmx16G"
lazy val root = (project in file("."))
  .settings(
    name := "extended_model"
  )

resolvers += "Akka library repository".at("https://repo.akka.io/maven")
val AkkaVersion = "2.8.5"
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-remote" % AkkaVersion
libraryDependencies += "io.spray" %%  "spray-json" % "1.3.6"
libraryDependencies += "org.postgresql" % "postgresql" % "42.7.3"
libraryDependencies += "com.zaxxer" % "HikariCP" % "5.1.0"
libraryDependencies += "tech.ant8e" %% "uuid4cats-effect" % "0.5.0"
