package com.github.j5ik2o.pekko.persistence.dynamodb.utils

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.{
  CreateContainerCmd,
  CreateContainerResponse,
  PullImageCmd,
  RemoveContainerCmd,
  StartContainerCmd
}
import com.github.j5ik2o.dockerController.dynamodbLocal.DynamoDBLocalController
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, Method, Proxy }

class DynamoDBContainerStartFailureSpec extends AnyFreeSpec with Matchers {

  Vector(true, false).foreach { autoClose =>
    s"a failed start respects the borrowed controller's client ownership (autoClose=$autoClose)" in {
      var ownerCloses  = 0
      var helperCloses = 0
      var removed      = false
      val ownerClient  = trackingClient(() => ownerCloses += 1)
      val helperClient = trackingClient(() => helperCloses += 1)
      val failure      = new IllegalStateException("synthetic start failure")
      val sharedController = new DynamoDBLocalController(ownerClient, isDockerClientAutoClose = autoClose)(0) {
        private var currentId: Option[String]                                    = None
        override def containerId: Option[String]                                 = currentId
        override def pullImageIfNotExists(f: PullImageCmd => PullImageCmd): Unit = ()
        override def createContainer(f: CreateContainerCmd => CreateContainerCmd): CreateContainerResponse = {
          currentId = Some("owned-container")
          new CreateContainerResponse()
        }
        override def startContainer(f: StartContainerCmd => StartContainerCmd): Unit = throw failure
        override def removeContainer(f: RemoveContainerCmd => RemoveContainerCmd): Unit = {
          removed = true
          currentId = None
        }
      }
      class BorrowingHelper extends DockerControllerHelperUtil {
        override protected val dockerClient: DockerClient     = helperClient
        override lazy val controller: DynamoDBLocalController = sharedController
        def closeTransport(): Unit                            = dockerHttpClient.close()
      }
      val helper = new BorrowingHelper
      try {
        intercept[IllegalStateException](helper.startContainer()) should be theSameInstanceAs failure
        removed shouldBe true
        sharedController.containerId shouldBe None
        ownerCloses shouldBe (if (autoClose) 1 else 0)
        helperCloses shouldBe 0
      } finally helper.closeTransport()
    }
  }

  private def trackingClient(onClose: () => Unit): DockerClient =
    Proxy
      .newProxyInstance(
        classOf[DockerClient].getClassLoader,
        Array[Class[_]](classOf[DockerClient]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
            if (method.getName == "close") {
              onClose()
              null
            } else throw new UnsupportedOperationException(method.getName)
        }
      ).asInstanceOf[DockerClient]
}
