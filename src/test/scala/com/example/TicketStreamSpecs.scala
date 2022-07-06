package com.example

import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl.{ActorTestKit, BehaviorTestKit, ScalaTestWithActorTestKit}
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.example.CustomerRegistry.CurrentTimeLapse
import com.example.TicketStream.{CreateStream, GetCurrentTimeLapse, Start, ticketsUrl}
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.util.concurrent.TimeUnit

class TicketStreamSpecs extends
   AnyWordSpecLike with Matchers with ScalatestRouteTest with BeforeAndAfterAll{

  val baseUrl = "zendesk.com/api/v2"
  implicit val timeout: Timeout = Timeout.apply(15, TimeUnit.SECONDS)
  val defaultPerPage = 5
   val testKit = ActorTestKit()
  implicit def typedSystem = testKit.system
  "TicketStream" should {
    "Create http request stream when CreateStream message is received" in {
      val probe = testKit.createTestProbe[TicketStream.Command]("ticket-probe")
      probe ! Start
      probe.expectMessageType[TicketStream.Start.type]
    }
    "Return the timelapse when message GetCurrentTimeLapse is received" in {
      val probe = testKit.createTestProbe[CustomerRegistry.Command]
      val ticket = testKit.spawn(TicketStream(baseUrl, Customer("lekan", "token", 778828), probe.ref))
      ticket ! GetCurrentTimeLapse(probe.ref)
      probe.expectMessageType[CurrentTimeLapse]
    }
    "CreateStream when start is receieved" in  {
      val customer = Customer("lekan", "token", 778828)
      val parent = BehaviorTestKit(CustomerRegistry(timeout))
      val testKit = BehaviorTestKit(TicketStream(baseUrl,  customer, parent.ref))
      testKit.run(Start)
      val im = testKit.selfInbox()
      val url = Uri(ticketsUrl.format(customer.domain, baseUrl, customer.startTime, defaultPerPage))
      im.expectMessage(Start)
    }
  }

}
