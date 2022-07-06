package com.example

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, StatusCodes, headers}
import com.example.TicketStream.{Start, ticketsUrl}

import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

final case class Customer(domain: String, token: String, startTime: Long)
import akka.util.Timeout

object CustomerRegistry {
  sealed trait Command
  final case class CreateCustomer(user: Customer, replyTo: ActorRef[ActionPerformed]) extends Command
  final case class GetCustomerStream(domain: String, replyTo: ActorRef[GetCustomerStreamResponse]) extends Command
  final case class DeleteUser(name: String, replyTo: ActorRef[ActionPerformed]) extends Command
  final case class CreateStream(ref: ActorRef[TicketStream.Command]) extends Command
  final case class CurrentTimeLapse(time: Duration) extends Command
  final case class GetCurrentTimeLapse(domain: String, replyTo: ActorRef[ActionPerformed]) extends Command
  final case class GetCustomerStreamResponse(maybeUser: Option[Customer])
  final case class ActionPerformed(description: String, success: Boolean)
  final case class RestartTicketStream(customer: Customer) extends Command


  val baseUrl = "zendesk.com/api/v2"
  def apply(implicit timeout: Timeout): Behavior[Command] = registry

  private def registry(implicit timeout: Timeout): Behavior[Command] = {
    Behaviors.setup{context =>
      implicit val ec: ExecutionContextExecutor = context.system.executionContext
      implicit val system: ActorSystem[Nothing] = context.system
      implicit val ctx = context
      Behaviors.supervise{
        Behaviors.receiveMessage[Command](behave).receiveSignal {
          case (context, PostStop) =>
            context.log.info(s"Actor ${context.self.path} stopped due to an error")
            Behaviors.same
        }
      }.onFailure(SupervisorStrategy.restartWithBackoff(
        FiniteDuration(5, TimeUnit.SECONDS), FiniteDuration(20, TimeUnit.SECONDS), 0.2
      ).withMaxRestarts(5)
      )
    }
  }

  def behave(implicit context: ActorContext[Command], executionContext: ExecutionContext, timeout: Timeout): PartialFunction[Command, Behavior[Command]] = {
    case CreateStream(ref) =>
      ref ! Start
      Behaviors.same
    case CreateCustomer(customer, replyTo) =>
      //TODO validate customer token here
      val children = context.children.find(_.asInstanceOf[ActorRef[TicketStream.Command]].path.name == s"${customer.domain}-stream-actor")
      children match {
        case Some(_) =>
          replyTo ! ActionPerformed(s"Invalid domain already exist", success = false)
        case None =>
          val streamActor = context.spawn(TicketStream(baseUrl, customer, context.self), s"${customer.domain}-stream-actor")
          context.log.info(s"New customer ${customer.domain} added: new actor created : ${streamActor.toString} creating tickets streaming")
          context.watch(streamActor)
          replyTo ! ActionPerformed(s"Customer ${customer.domain} created.", success = true)
          context.self ! CreateStream(streamActor)
      }
      Behaviors.same
    case GetCurrentTimeLapse(domain, replyTo) =>
      val maybeChild = context.children.find(_.asInstanceOf[ActorRef[TicketStream.Command]].path.name == s"$domain-stream-actor")
      maybeChild match {
        case Some(value) =>
          val actorRef = value.asInstanceOf[ActorRef[TicketStream.Command]]
          val res = actorRef.ask(TicketStream.GetCurrentTimeLapse)(timeout, context.system.scheduler)
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
    case RestartTicketStream(customer) =>
      context.log.error(s"Ticket streaming actor for ${customer.domain} failed... trying to  restarting the stream")
      val ref = context.children
        .find(_.asInstanceOf[ActorRef[TicketStream.Command]].path.name == s"${customer.domain}-stream-actor")
        .map(_.asInstanceOf[ActorRef[TicketStream.Command]])
      ref match {
        case Some(value) =>
          value ! Start
        case None =>
          context.log.warn("For some reason we cannot find the failed and restarted actor.. moving on")
      }
      Behaviors.same
    case _ => Behaviors.same
  }

  def validateCustomer(customer: Customer)(implicit system: ActorSystem[_], ec: ExecutionContext): Future[Boolean] = {
    val header = List(headers.RawHeader("Authorization", s"Bearer ${customer.token}"))
    val request = HttpRequest(uri = ticketsUrl.format(customer.domain, baseUrl, customer.startTime, 1), headers = header)
    val response = Http().singleRequest(request)
    response map { res =>
      res.status match {
        case StatusCodes.OK =>
          true
        case _ =>
          false
      }
    }
  }
}
