package com.example

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{Link, LinkValue}
import akka.http.scaladsl.model.{HttpHeader, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri, headers}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.example.TicketStream.Command
import org.slf4j.Logger

import scala.collection.{Seq, immutable}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class HttpClientException(msg: String) extends  Error{
  override def getMessage: String = msg
}

case class BadCredentials(error: String) extends Error
class HttpClient(entityMapper: HttpResponse => Future[Option[ZendeskEntity]], token: String, log: Logger)(implicit materializer: Materializer, system: ActorSystem[Command])  {
  import JsonFormats._

  case class PageMeta(next_page: Option[String], previous_page: Option[String])
  case object LimitExceeded extends Error
  val defaultHeaders = List(headers.RawHeader("Authorization", s"Bearer $token"))



  private def nextUriii(heads: Seq[HttpHeader], meta: PageMeta): Either[Option[Uri], Option[LimitExceeded.type]] = {
    val linkHeader = heads.find(_.is("x-rate-limit-remaining")).map(e => Integer.parseInt(e.value()))
    val nextUri = meta.next_page
    linkHeader match {
      case Some(rateLimitRemaining) if rateLimitRemaining > 0 =>
        Left(nextUri.map(Uri(_)))
      case Some(_) =>
        Right(Some(LimitExceeded))
      case None =>
        throw HttpClientException("rate limit header not found in header")
    }
  }

  private def nextUri(meta: PageMeta):Option[Uri] = {
    meta.next_page.map(Uri(_))
  }

  def makeRequest(uri: Option[Uri]): Source[Either[Error, ZendeskEntity], NotUsed] = {
    Source.unfoldAsync(uri)(paginationChain)
  }

  def paginationChain(uri: Option[Uri]): Future[Option[(Option[Uri], Either[Error, ZendeskEntity])]] = {
    implicit val ec: ExecutionContext = system.executionContext
    (uri match {
      case Some(uri) =>
        val request = HttpRequest(method = HttpMethods.GET, uri = uri, headers = defaultHeaders)
        Http().singleRequest(request) flatMap { response =>
          response.status match {
            case StatusCodes.Unauthorized =>
             Unmarshal(response).to[BadCredentials].map { resp =>
                log.error(s"Unauthorized request, please check provided credentials: ${resp}")
                Some(None -> Left(resp))
              }
            case StatusCodes.OK =>
              convert(response) map { resp =>
                log.debug(s"response for request: ${request.uri}: data gotten ${resp}")
                if(resp._1.isDefined){
                  val rateLimit = rateLimitWatch(response.headers, uri,  resp._1)
                  getNextRequest(resp._1.get) match {
                    case Some(request) if rateLimit.isEmpty =>
                      log.debug(s"Not done yet.. Fetching next page.. : ${request.toString()}")
                      Some(Some(request) -> Right(resp._1.get))
                    case Some(value) if rateLimit.isDefined =>
                      log.debug(s"Not done yet, but we  are close to rate limit... continuing to the next page ${request.toString()} in 60 seconds. ")
                      Some(None -> Right(rateLimit.get.copy(url = value)))
                    case None =>
                      log.debug(s"no next request found: end of chain: last page data:  ${resp._1.size}")
                      Some(None -> Right(resp._1.get))
                  }
                }else {
                  log.error("Unable to unmarshal entity to a valid type")
                  Some(None -> Left(HttpClientException("Unable to unmarshal entity to a valid type")))
                }
              }
            case StatusCodes.TooManyRequests =>
              val headers = response.headers
              headers.find(_.is("retry-after")) match {
                case Some(value) =>
                  Try(Integer.parseInt(value.value())).toOption match {
                    case Some(value) =>
                      log.info(s"rate limit exceeded while. retrying in $value seconds")
                      Future.successful(Some(None, Right(RateLimitRetryAfter(value, uri, None))))
                    case None =>
                      log.error("Invalid state. Unable to parse retry-after time after TooManyRequests aborting without finishing")
                      Future.successful(Some(None -> Left(HttpClientException("Invalid state. Unable to parse Retry-after time after TooManyRequests aborting without finishing"))))
                  }

                case None =>
                  log.error("Invalid state. we got too many request without Retry-after time")
                  Future.successful(Some(None -> Left(HttpClientException("Invalid state. we got too many request without Retry-after time"))))
              }
            case _ =>
              Unmarshal(response).to[String].foreach(e => log.error(s"uncaught response retuned : $request. \n details: $e"))
              Future.successful(Some(None -> Left(HttpClientException(s"uncaught response returned : $request"))))
          }
        }
      case None =>
        Future.successful(None)
    }).recoverWith{
      case e:Exception =>
        log.error(s"An unexpected error occured while processing requests caused by ${e.getCause}. message:  ${e.getMessage}")
        Future.failed(e)
    }
  }
  private def getNextRequest(entity: ZendeskEntity): Option[Uri] = entity match {
    case tickets: Tickets if !tickets.end_of_stream && tickets.next_page.isDefined =>
      tickets.next_page.map(Uri(_))
    case audits: Audits => audits.next_page.map(Uri(_))
    case _ => None
  }

  private def rateLimitWatch(headers: Seq[HttpHeader], uri: Uri, date: Option[ZendeskEntity]): Option[RateLimitRetryAfter] = {
    headers.find(_.is("x-rate-limit-remaining")) match {
      case Some(value) =>
        Try(Integer.parseInt(value.value())).toOption match {
          case Some(value) if value == 690 => //for some reason the x-rate-limit is 700 while the rate limit calls allowed is 10
            Some(RateLimitRetryAfter(60, uri, date))
          case _ =>
            None
        }
      case None =>
        None
    }
  }
  def convert(response: HttpResponse)(implicit executionContext: ExecutionContext, materializer: Materializer): Future[(Option[ZendeskEntity], immutable.Seq[HttpHeader])] = {
    val heads = response.headers
    response.status match {
      case StatusCodes.OK =>
        entityMapper(response).map(e => e -> heads)
      case _ =>
        Unmarshal(response).to[String].foreach(log.error)
        Future(None, heads)
    }
  }
}
