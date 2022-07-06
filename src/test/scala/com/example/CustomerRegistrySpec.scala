package com.example

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, BehaviorTestKit, ManualTime, ScalaTestWithActorTestKit, TestInbox}
import com.example.CustomerRegistry.{ActionPerformed, CreateCustomer, CreateStream, GetCurrentTimeLapse}
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}
import akka.actor.testkit.typed.Effect._
import akka.actor.typed.Props
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import java.io.File
class CustomerRegistrySpec extends AnyWordSpec with   BeforeAndAfterAll with Matchers{
  val testKit = ActorTestKit()
  implicit def typedSystem = testKit.system

  implicit val timeout: Timeout = Timeout.create(testKit.system.settings.config.getDuration("ticketing.routes.ask-timeout"))

  val baseUrl =  "zendesk.com/api/v2"
  "CustomerRegistry" should {
    val registry = testKit.spawn(CustomerRegistry(timeout))
    val probe = testKit.createTestProbe[CustomerRegistry.ActionPerformed]()
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
      val actionPerformed = BehaviorTestKit(Behaviors.empty[ActionPerformed])
      val testKit = BehaviorTestKit(CustomerRegistry(timeout))
      testKit.run(CustomerRegistry.CreateCustomer(Customer("lekan", "token", 66738), actionPerformed.ref))
      val eff = testKit.expectEffectType[Spawned[TicketStream.Command]]
      eff.childName shouldEqual "lekan-stream-actor"
    }
  }

}
