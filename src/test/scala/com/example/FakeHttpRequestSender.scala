package com.example

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model._
import spray.json._

import java.time.OffsetDateTime
import java.util.NoSuchElementException
import scala.concurrent.Future
class FakeHttpRequestSender(fakeType: String) extends HttpRequestSender{

  import JsonFormats._
  var counter = 0

  val dummyResponses1 = Iterator[Tickets](
    Tickets(
      Seq(Ticket(4599, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      false,
      1656601420
    ),
    Tickets(
      Seq(Ticket(4600, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      false,
      1656601421
    ),
    Tickets(
      Seq(Ticket(4601, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      false,
      1656601422
    ),
    Tickets(
      Seq(Ticket(4602, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      true,
      1656601423
    )
  )

  val dummyResponses2: Iterator[Tickets] = Iterator[Tickets](
    Tickets(
      Seq(Ticket(4599, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      false,
      1656601420
    ),
    Tickets(
      Seq(Ticket(4600, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      false,
      1656601421
    ),
    Tickets(
      Seq(Ticket(4601, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      false,
      1656601422
    ),
    Tickets(
      Seq(Ticket(4602, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      false,
      1656601423
    ),
    Tickets(
      Seq(Ticket(4603, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      false,
      1656601424
    ),
    Tickets(
      Seq(Ticket(4604, OffsetDateTime.parse("2022-05-11T15:26:49Z"), OffsetDateTime.parse("2022-05-11T15:26:49Z"))),
      Some("https://d3v-kaizo.zendesk.com/api/v2/incremental/tickets.json?per_page=20&start_time=1656601420"),
      None,
      true,
      1656601425
    )
  )
  override def sendRequest(request: HttpRequest): Future[HttpResponse] = {
    try{
      if(counter > 3) throw new NoSuchElementException("limit")
      val payload: Tickets = if (fakeType =="limit") dummyResponses2.next() else dummyResponses1.next()
      val resp = HttpResponse(status = StatusCodes.OK, entity = HttpEntity(string = payload.toJson.prettyPrint, contentType = ContentTypes.`application/json`))
      counter += 1
      Future.successful(resp)
    }catch {
      case e: NoSuchElementException =>
        val headers = Seq(RawHeader("retry-after", 3.toString))
        val resp = HttpResponse(headers = headers,status = StatusCodes.TooManyRequests, entity = HttpEntity(contentType = ContentTypes.`application/json`, string = ""))
        counter = 0
        Future.successful(resp)
    }
  }
}
object FakeHttpRequestSender {
  def apply(fakeType: String): HttpRequestSender = new FakeHttpRequestSender(fakeType)
}