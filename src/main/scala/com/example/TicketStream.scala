package com.example

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Dispatchers, PostStop, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.{HttpResponse, Uri, headers}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.example.CustomerRegistry.CurrentTimeLapse

import java.io.FileOutputStream
import java.time.{Duration, Instant, LocalDateTime, OffsetDateTime}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Try, Using}

trait TicketingDomain

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
case class RateLimitRetryAfter(url: Uri, currentPageData:  Option[ZendeskEntity]) extends ZendeskEntity{
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
  final case class Poll(url: Uri, startTime: Long) extends Command
  final case class StreamData(data: ZendeskEntity) extends Command
  def apply(baseUrl: String, customer: Customer): Behavior[Command] = handle(customer, baseUrl)
  val auditUrl = "https://%s.%s/tickets/%s/audits.json?per_page=%d"
  val ticketsUrl = "https://%s.%s/incremental/tickets.json?start_time=%d&per_page=%d"

  def handle(customer: Customer, baseUrl: String): Behavior[Command] = Behaviors.setup{context =>
    var currentTimeStamp: Long = 0L
    implicit val system: ActorSystem[Command] = context.system.asInstanceOf[ActorSystem[Command]]
    import system.executionContext
    implicit val materializer: Dispatchers = context.system.dispatchers
    // FIXME for some unknown reason, ActorTestKit couldnt read configuration so had to hardcode for tests to work
    val defaultPerPage = 5 //system.settings.config.getInt("ticketing.app.default-data-per-page")
    val defaultPollingTime = 20 // system.settings.config.getInt("ticketing.app.default-polling-time")
    val defaultRequestPerMinute = 10 // system.settings.config.getInt("ticketing.app.default-request-per-minute")
    val defaultRateLimitWait = 60 //system.settings.config.getInt("ticketing.app.default-rate-limit-wait")
    context.self ! Start
    Behaviors.supervise{
      Behaviors.receiveMessage[Command]{
        case ProcessStream(source: Source[Either[Error, ZendeskEntity], NotUsed]) =>
          source
            .mapAsync(3) {
              case Left(value) =>
                system.log.error(s"couldn't fetch a page: ${value.getMessage}")
                Future.successful(())
              case Right(value) =>
                context.self ! StreamData(value)
                Future.successful(())
            }.run()
          Behaviors.same
        case CreateStream(uri: Uri) =>
          val server = HttpServer()
          val client = new HttpClient(entityMapper = converter, customer.token, server, log = system.log)
          context.log.info(s"Stream created for customer: ${customer.domain}.. starting request chain with initial uri: ${uri.toString()}")
          val source = client.makeRequest(Some(uri, defaultRequestPerMinute))
          context.self ! ProcessStream(source)
          Behaviors.same
        case Start =>
          val uri = Uri(ticketsUrl.format(customer.domain, baseUrl, customer.startTime, defaultPerPage))
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
        case Poll(url, time) =>
          context.system.scheduler.scheduleOnce(FiniteDuration.apply(defaultPollingTime, TimeUnit.SECONDS), () => context.self ! CreateStream(url))
          context.log.info(s"Polling for new data at timestamp: ${time} with url $url in $defaultPollingTime seconds ")
          Behaviors.same
        case StreamData(data) =>
          data match {
            case Tickets(tickets, _, _, end_of_stream, currentStreamTime) =>
              if(end_of_stream){
                context.self ! CreateStream(auditUrl.format(customer.domain, baseUrl, tickets.head.id, defaultPerPage))
                context.self ! Poll(ticketsUrl.format(customer.domain, baseUrl, currentStreamTime, defaultPerPage), currentStreamTime)
              }
              context.self ! CurrentStreamTime(currentStreamTime)
              tickets.foreach(data => println(s"Ticket: \t<Domain = ${customer.domain}, ticketId = ${data.id}, created_at = ${data.created_at}, updated_at = ${data.updated_at}>\n"))
            case Audits(audits, _, _) =>
              audits.foreach(data => println(s"Audit: \t <Ticket = ${data.ticket_id}, id = ${data.id}, created_at = ${data.created_at}\n"))
            case RateLimitRetryAfter(uri, currentPageData) =>
              context.log.info(s"Rate limit exceeded, retrying again in $defaultRateLimitWait seconds")
              system.scheduler.scheduleOnce(FiniteDuration.apply(defaultRateLimitWait, TimeUnit.SECONDS), () => context.self ! CreateStream(uri))
              if(currentPageData.isDefined) context.self ! StreamData(currentPageData.asInstanceOf[ZendeskEntity])
          }
          Behaviors.same
        case _ =>
          Behaviors.same
      }.receiveSignal {
        case (context, PostStop) =>
          context.log.info(s"Actor ${context.self.path} stopped due to an error")
          Behaviors.same
      }
    }.onFailure(SupervisorStrategy.restartWithBackoff(
      FiniteDuration(5, TimeUnit.SECONDS), FiniteDuration(20, TimeUnit.SECONDS), 0.2
    ).withMaxRestarts(5)
    )
  }

  def converter(httpResponse: HttpResponse)(implicit mat: Materializer, executionContext: ExecutionContext): Future[Option[ZendeskEntity]] = {
    Try(Unmarshal(httpResponse).to[ZendeskEntity])
      .fold(
        _ => Future.successful(None),
        fb => fb.map(Some(_))
      )
  }
}
