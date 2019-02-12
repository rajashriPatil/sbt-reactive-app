import com.typesafe.sbt.packager.docker.DockerChmodType
import Dependencies._
import scala.sys.process.Process
import scala.util.control.NonFatal

version in ThisBuild      := "0.1.0"
organization in ThisBuild := "com.example"
scalaVersion in ThisBuild := "2.12.8"

lazy val check = taskKey[Unit]("check")

lazy val root = (project in file("."))
  .enablePlugins(SbtReactiveAppPlugin)
  .settings(
    name := "bootstrap-kapi-demo",
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-Xlint",
      "-Yno-adapted-args",
    ),
    libraryDependencies ++= Seq(
      akkaCluster,
      akkaClusterSharding,
      akkaClusterTools,
      akkaSlj4j,
      logback,
      scalaTest
    ),
    rpEnableAkkaClusterBootstrap := true,
    rpAkkaClusterBootstrapSystemName := "hoboken1",

    // this logic was taken from test.sh
    check := {
      val s = streams.value
      val nm = name.value
      val v = version.value
      val namespace = sys.env.get("OC_PROJECT").getOrElse("reactivelibtest1")
      val kubectl = Deckhand.kubectl(s.log)
      val docker = Deckhand.docker(s.log)
      val yamlDir = baseDirectory.value / "kubernetes"

      try {
        if (!Deckhand.isOpenShift) {
          kubectl.tryCreate(s"namespace $namespace")
          kubectl.setCurrentNamespace(namespace)
          kubectl.apply(Deckhand.mustache(yamlDir / "rbac.mustache"),
            Map(
              "namespace"       -> namespace
            ))
          kubectl.apply(Deckhand.mustache(yamlDir / "akka-cluster.mustache"),
            Map(
              "image"           -> s"$nm:$v",
              "imagePullPolicy" -> "Never"
            ))
        } else {
          kubectl.command(s"policy add-role-to-user system:image-builder system:serviceaccount:$namespace:default")
          kubectl.apply(Deckhand.mustache(yamlDir / "rbac.mustache"),
            Map(
              "namespace"       -> namespace
            ))
          docker.tag(s"$nm:$v docker-registry-default.centralpark.lightbend.com/$namespace/$nm:$v")
          docker.push(s"docker-registry-default.centralpark.lightbend.com/$namespace/$nm")
          s.log.info("applying openshift.yml")
          kubectl.apply(Deckhand.mustache(yamlDir / "akka-cluster.mustache"),
            Map(
              "image"           -> s"docker-registry-default.centralpark.lightbend.com/$namespace/$nm:$v",
              "imagePullPolicy" -> "Always"
            ))
        }
        kubectl.waitForPods(3)
        kubectl.describe("pods")
        kubectl.checkAkkaCluster(3, _.contains(nm))
      } finally {
        kubectl.delete(s"services,pods,deployment --all --namespace $namespace")
        kubectl.waitForPods(0)
      }

    }
  )