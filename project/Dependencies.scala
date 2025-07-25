import sbt._

object Dependencies {

  object Versions {
    val scala212Version = "2.12.20"
    val scala213Version = "2.13.16"
    val scala3Version   = "3.6.4"

    val scalaCollectionCompatVersion = "2.13.0"

    val pekkoVersion = "1.1.1"

    val logbackVersion      = "1.5.18"
    val slf4jVersion        = "1.7.36"
    val ficusVersion        = "1.5.2"
    val awsSdkV1Version     = "1.12.788"
    val awsSdkV1DaxVersion  = "1.0.230341.0"
    val awsSdkV2Version     = "2.32.9"
    val reactiveAwsDynamoDB = "1.2.6"

    val scalaTest32Version      = "3.2.16"
    val scalaJava8CompatVersion = "1.0.2"

    val reactiveStreamsVersion = "1.0.3"
    val nettyVersion           = "4.1.33.Final"

  }

  import Versions._

  object iheart {
    val ficus = "com.iheart" %% "ficus" % ficusVersion
  }

  object slf4j {
    val api        = "org.slf4j" % "slf4j-api"    % slf4jVersion
    val julToSlf4J = "org.slf4j" % "jul-to-slf4j" % slf4jVersion
  }

  object fasterxml {

    val jacksonModuleScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.19.2"

  }

  object softwareamazon {
    val dynamodb = "software.amazon.awssdk" % "dynamodb" % awsSdkV2Version
  }

  object logback {
    val classic = "ch.qos.logback" % "logback-classic" % logbackVersion
  }

  object amazonaws {
    val dynamodb = "com.amazonaws" % "aws-java-sdk-dynamodb" % awsSdkV1Version
    val dax      = "com.amazonaws" % "amazon-dax-client"     % awsSdkV1DaxVersion
  }

  object pekko {
    def slf4j(version: String): ModuleID                = "org.apache.pekko" %% "pekko-slf4j"                 % version
    def stream(version: String): ModuleID               = "org.apache.pekko" %% "pekko-stream"                % version
    def testkit(version: String): ModuleID              = "org.apache.pekko" %% "pekko-testkit"               % version
    def streamTestkit(version: String): ModuleID        = "org.apache.pekko" %% "pekko-stream-testkit"        % version
    def persistence(version: String): ModuleID          = "org.apache.pekko" %% "pekko-persistence"           % version
    def persistenceTestkit(version: String): ModuleID   = "org.apache.pekko" %% "pekko-persistence-testkit"   % version
    def persistenceQuery(version: String): ModuleID     = "org.apache.pekko" %% "pekko-persistence-query"     % version
    def persistenceTyped(version: String): ModuleID     = "org.apache.pekko" %% "pekko-persistence-typed"     % version
    def persistenceTck(version: String): ModuleID       = "org.apache.pekko" %% "pekko-persistence-tck"       % version
    def serializationJackson(version: String): ModuleID = "org.apache.pekko" %% "pekko-serialization-jackson" % version
  }

  object scalatest {
    def scalatest(version: String) = "org.scalatest" %% "scalatest" % version
  }

  object javaDevJnv {
    val jna = "net.java.dev.jna" % "jna" % "5.17.0"
  }

  object scalaLangModules {
    val scalaCollectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % scalaCollectionCompatVersion
  }

}
