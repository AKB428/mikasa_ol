name := "ikazuchi_zero"

version := "1.0"

scalaVersion := "2.10.5"

libraryDependencies += "org.apache.spark" %% "spark-core" % "1.4.0"
libraryDependencies += "org.apache.spark" %% "spark-streaming" % "1.4.0"
libraryDependencies += "org.apache.kafka" % "kafka_2.10" % "0.8.2.1"
libraryDependencies += "org.apache.spark" % "spark-streaming-twitter_2.10" % "1.4.0"


resolvers += "Atilika Open Source repository" at "http://www.atilika.org/nexus/content/repositories/atilika"

libraryDependencies ++= Seq(
  "org.atilika.kuromoji" % "kuromoji" % "0.7.7"
)