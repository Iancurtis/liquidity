name := """liquidity-common"""

organization := "com.dhpcs"

version := "0.16.0"

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
  "com.dhpcs" %% "play-json-rpc" % "0.1.0",
  "com.google.guava" % "guava" % "18.0",
  "com.google.code.findbugs" % "jsr305" % "3.0.0",
  "com.dhpcs" %% "play-json-rpc" % "0.1.0" % Test classifier "tests",
  "org.scalatest" % "scalatest_2.11" % "2.2.4" % Test
)
