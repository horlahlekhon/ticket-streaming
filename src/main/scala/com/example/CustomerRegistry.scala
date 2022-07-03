package com.example

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import com.example.TicketStream.Start

import java.time.Duration
import scala.concurrent.ExecutionContextExecutor

final case class Customer(domain: String, token: String, startTime: Long)
import akka.util.Timeout

object CustomerRegistry {
  sealed trait Command
  final case class CreateCustomer(user: Customer, replyTo: ActorRef[ActionPerformed]) extends Command
  final case class GetCustomerStream(domain: String, replyTo: ActorRef[GetCustomerStreamResponse]) extends Command
  final case class DeleteUser(name: String, replyTo: ActorRef[ActionPerformed]) extends Command
  final case class CreateStream(customer: Customer) extends Command
  final case class CurrentTimeLapse(time: Duration) extends Command
  final case class GetCurrentTimeLapse(domain: String, replyTo: ActorRef[ActionPerformed]) extends Command
  final case class GetCustomerStreamResponse(maybeUser: Option[Customer])
  final case class ActionPerformed(description: String, success: Boolean)



  def apply(baseUrl: String): Behavior[Command] = registry(baseUrl)

  private def registry(baseUrl: String): Behavior[Command] = {
    Behaviors.receive {(context, msg) =>
      implicit val timeout: Timeout = Timeout.create(context.system.settings.config.getDuration("ticketing.routes.ask-timeout"))
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContextExecutor = context.system.executionContext
      msg match{
        case CreateStream(customer) =>
          val streamActor =   context.spawn(TicketStream(baseUrl, customer), s"${customer.domain}-stream-actor")
          context.log.info(s"New customer ${customer.domain} added: new actor created : ${streamActor.toString} creating tickets streaming")
          context.watch(streamActor)
          streamActor ! Start
          Behaviors.same
        case CreateCustomer(customer, replyTo) =>
          // TODO validate token here, prolly just call a simple endpoint
          val children = context.children.find(_.asInstanceOf[ActorRef[TicketStream.Command]].path.name == s"${customer.domain}-stream-actor")
          children match {
            case Some(_) =>
              replyTo ! ActionPerformed(s"Invalid domain already exist", success = false)
            case None =>
              replyTo ! ActionPerformed(s"Customer ${customer.domain} created.", success = true)
              context.self ! CreateStream(customer)
          }
          Behaviors.same
        case GetCurrentTimeLapse(domain, replyTo) =>
          val maybeChild = context.children.find(_.asInstanceOf[ActorRef[TicketStream.Command]].path.name == s"${domain}-stream-actor")
          maybeChild match {
            case Some(value) =>
              val actorRef = value.asInstanceOf[ActorRef[TicketStream.Command]]
              val res= actorRef.ask(TicketStream.GetCurrentTimeLapse)
              res.map {
                case CurrentTimeLapse(time) =>
                  replyTo ! ActionPerformed(s"time difference between the stream and real time is - ${time.abs().toMinutes} minutes", true)
                case _ =>
                  replyTo ! ActionPerformed("Something went wrong while getting the time lapse", false)
              }
            case None =>
              replyTo ! ActionPerformed("Unknown domain, please enter a valid domain", false)
          }
          Behaviors.same
        case _ => Behaviors.same
      }

    }
  }
}
