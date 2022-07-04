package com.example

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Dispatchers}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.{HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.example.CustomerRegistry.CurrentTimeLapse

import java.time.{Duration, Instant, LocalDateTime, OffsetDateTime}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

trait TicketingDomain

// TODO change to ZendeskResponse
sealed trait ZendeskEntity{
  def data: Seq[TicketingDomain]
}
case class Ticket(id: Long, created_at: OffsetDateTime, updated_at: OffsetDateTime) extends TicketingDomain
case class Tickets(tickets: Seq[Ticket], next_page: Option[String], previous_page: Option[String], end_of_stream: Boolean, end_time: Long) extends ZendeskEntity{
  override def data: Seq[TicketingDomain] = tickets
}
case class Audit(id: Long, ticket_id: Long, created_at: OffsetDateTime) extends TicketingDomain
case class Audits(audits: Seq[Audit], next_page: Option[String], previous_page: Option[String]) extends ZendeskEntity{
  override def data: Seq[TicketingDomain] = audits
}
case class AuditCount(count: Int)
case class RateLimitRetryAfter(seconds: Int, url: Uri, currentPageData:  Option[ZendeskEntity]) extends ZendeskEntity{
  override def data: Seq[TicketingDomain] = {
    currentPageData match {
      case Some(value) => value.data
      case None => Seq.empty[TicketingDomain]
    }
  }
}



object TicketStream {
  import JsonFormats._
  sealed trait Command
  final case class CreateStream(uri: Uri) extends Command
  final case class ProcessStream(source: Source[Either[Error, ZendeskEntity], NotUsed]) extends Command
  final case object Start extends Command
  final case class CurrentStreamTime(time: Long) extends Command
  final case class GetCurrentTimeLapse(replyTo: ActorRef[CustomerRegistry.Command]) extends Command

  def apply(baseUrl: String, customer: Customer): Behavior[Command] = handle(customer, baseUrl)

  val auditUrl = "https://%s.%s/tickets/%s/audits.json?per_page=100"
  val ticketsUrl = "https://%s.%s/incremental/tickets.json?start_time=%d&per_page=10"

  //TODO implement polling every 5 seconds
  def handle(customer: Customer, baseUrl: String): Behavior[Command] =
  Behaviors.receive{(context, msg) =>
    implicit val system: ActorSystem[Command] = context.system.asInstanceOf[ActorSystem[Command]]
    import system.executionContext
    implicit val materializer: Dispatchers = context.system.dispatchers
    var currentTimeStamp: Long = customer.startTime
    msg match{
      case ProcessStream(source: Source[Either[Error, ZendeskEntity], NotUsed]) =>
        source
          .mapAsync(3) {
            case Left(value) =>
              system.log.error(s"couldn't fetch a page: ${value.getMessage}")
              Future(Seq.empty[ZendeskEntity])
            case Right(value) =>
              system.log.debug(s"Current actor: ${context.self.path.name}")
              value match {
                case Tickets(tickets, _, _, end_of_stream, currentStreamTime) =>
                  if(end_of_stream){
                    context.self ! CreateStream(auditUrl.format(customer.domain, baseUrl, tickets.head.id))
                    context.self ! Poll(currentTimeStamp)
                  }
                  context.self ! CurrentStreamTime(currentStreamTime)
                  Future(tickets)
                case Audits(audits, _, _) =>
                  Future(audits)
                case RateLimitRetryAfter(seconds, uri, Some(currentPageData)) =>
                  system.scheduler.scheduleOnce(FiniteDuration.apply(seconds, "seconds"), () => context.self ! CreateStream(uri))
                  Future(currentPageData.data)
                case RateLimitRetryAfter(_, _, None) =>
                  Future(Seq.empty[ZendeskEntity])
                case _ => Future(Seq.empty[ZendeskEntity])
              }
          }
          .mapConcat(identity)
          .runForeach(e => println(e))
        Behaviors.same
      case CreateStream(uri: Uri) =>
        val client = new HttpClient(token = customer.token, entityMapper = converter, log = system.log)
        context.log.info(s"Stream created for customer: ${customer.domain}.. starting request chain with initial uri: ${uri.toString()}")
        val source = client.makeRequest(Some(uri))
        context.self ! ProcessStream(source)
        Behaviors.same
      case Start =>
        val uri = Uri(ticketsUrl.format(customer.domain, baseUrl, customer.startTime))
        context.self ! CreateStream(uri)
        context.log.info(s"starting stream for actor: ${context.self.path.name}")
        Behaviors.same
      case CurrentStreamTime(time: Long) =>
        currentTimeStamp = time
        Behaviors.same
      case GetCurrentTimeLapse(replyTo) =>
        val streamTime = Instant.ofEpochSecond(currentTimeStamp)
        val durationBtw = Duration.between(streamTime, OffsetDateTime.now().toInstant)
        replyTo ! CurrentTimeLapse(durationBtw)
        Behaviors.same
      case _ =>
        Behaviors.same
    }
  }

  def converter(httpResponse: HttpResponse)(implicit mat: Materializer, executionContext: ExecutionContext): Future[Option[ZendeskEntity]] = {
    Try(Unmarshal(httpResponse).to[ZendeskEntity])
      .fold(
        _ => Future.successful(None),
        fb => fb.map(Some(_))
      )
  }


}
//d3v-kaizo-stream-actor--1109675513

//d3v-kaizo-stream-actor-2004099955
//d3v-kaizo-stream-actor--833775612