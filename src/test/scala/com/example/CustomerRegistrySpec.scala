package com.example

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, ManualTime, ScalaTestWithActorTestKit, TestInbox}
import com.example.CustomerRegistry.{ActionPerformed, CreateCustomer, CreateStream, GetCurrentTimeLapse}
import org.scalatest.wordspec.AnyWordSpecLike
import akka.actor.testkit.typed.Effect._
import akka.actor.typed.Props
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import java.io.File
class CustomerRegistrySpec extends
  ScalaTestWithActorTestKit(
    config = ConfigFactory.parseFile(new File("src/test/resources/application-test.conf"))
  )
  with AnyWordSpecLike{
  override implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("ticketing.routes.ask-timeout"))

  val baseUrl =  "zendesk.com/api/v2"
  "CustomerRegistry" should {
    val registry = testKit.spawn(CustomerRegistry(baseUrl))
    val probe = testKit.createTestProbe[CustomerRegistry.ActionPerformed]
    "send Action performed back when create customer is called" in {
      registry ! CreateCustomer(Customer("lekan", "token", 778828), probe.ref)
      probe.expectMessage(ActionPerformed("Customer lekan created.", true))
      registry ! CreateCustomer(Customer("lekan", "token", 778828), probe.ref)
      probe.expectMessage(ActionPerformed("Invalid domain already exist", false))
    }
    "return with current time lapse when GetCurrentTimeLapse is sent" in {
      registry ! GetCurrentTimeLapse("lekan", probe.ref)
      probe.expectMessageType[ActionPerformed]
    }
  }

  "CustomerRegistry" should {
    "Spawn a child actor when CreateStream is called" in {
      val child = Behaviors.receiveMessage[TicketStream.Command]{_ => Behaviors.same}
      val testKit = BehaviorTestKit(CustomerRegistry(baseUrl))
      testKit.run(CustomerRegistry.CreateStream(Customer("lekan", "token", 66738)))
      val eff = testKit.expectEffectType[Spawned[TicketStream.Command]]
      eff.childName shouldEqual "lekan-stream-actor"
    }
  }

}
