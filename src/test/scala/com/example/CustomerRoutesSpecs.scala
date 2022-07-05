package com.example

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.util.Timeout
import com.example.CustomerRegistry.ActionPerformed
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class CustomerRoutesSpecs  extends AsyncWordSpec with Matchers with ScalatestRouteTest with BeforeAndAfterAll{
  import JsonFormats._
  lazy val testKit = ActorTestKit()
  implicit def typedSystem = testKit.system
  implicit def default = RouteTestTimeout(FiniteDuration(20, TimeUnit.SECONDS))
  val baseUrl =  system.settings.config.getString("ticketing.app.base-url")
  implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("ticketing.routes.ask-timeout"))

  val registry = testKit.spawn(CustomerRegistry(baseUrl))
  val routes = new CustomerRoutes(registry)(typedSystem)

  "POST /customers" should {
    "create customer given correct payload" in {
      val customer = Customer("lekan", "", 5978)
      Post("/customers", customer) ~> routes.customerRoutes ~> check {
        status shouldEqual Created
        responseAs[ActionPerformed].success shouldEqual true
      }
    }
  }

  "GET /customers/<domain>" should {
    "Get the current time lag between stream and realtime" in {
      val customer = Customer("lekan", "", 5978)
      val re = Post("/customers", customer) ~> routes.customerRoutes
      Get("/customers/lekan") ~> routes.customerRoutes ~> check {
        status shouldEqual StatusCodes.OK
        val action = responseAs[ActionPerformed]
        action.success shouldEqual true
        action.description should include  ("time difference between the stream and real time is")
      }
    }
  }
}
