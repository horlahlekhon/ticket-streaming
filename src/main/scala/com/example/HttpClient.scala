package com.example

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
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

  val defaultHeaders = List(headers.RawHeader("Authorization", s"Bearer $token"))
  /*
  * Returns a Source that emits Option[Seq[GithubEntity]] and can be materialized somewhere..
  * @param: uri: the uri of request to be made
  * */
  def makeRequest(uri: Option[(Uri, Int)]): Source[Either[Error, ZendeskEntity], NotUsed] = {
    Source.unfoldAsync(uri)(paginationChain)
  }

  /*
  * Recursively make requests to a paginated page, given that the pagination
  * details exists in the message body
  * @param uri: An Option of uri of request to be made.*/
  def paginationChain(requestProps: Option[(Uri, Int)]): Future[Option[(Option[(Uri, Int)], Either[Error, ZendeskEntity])]] = {
    implicit val ec: ExecutionContext = system.executionContext
    (requestProps match {
      case Some(uri -> limit) if limit >= 1 =>
        log.info(s"Making number $limit request of iteration")
        val request = HttpRequest(method = HttpMethods.GET, uri = uri, headers = defaultHeaders)
        Http().singleRequest(request) flatMap { response =>
          response.status match {
            case StatusCodes.Unauthorized =>
             Unmarshal(response).to[BadCredentials].map { resp =>
                log.error(s"Unauthorized request, please check provided credentials: ${resp}")
                Some(None -> Left(resp))
              }
            case StatusCodes.OK =>
              processResponse(response, request -> limit)
            case StatusCodes.TooManyRequests =>
              val headers = response.headers
              tooManyRequests(headers, uri)
            case _ =>
              Unmarshal(response).to[String].foreach(e => log.error(s"uncaught response retuned : $request. \n details: $e"))
              Future.successful(Some(None -> Left(HttpClientException(s"uncaught response returned : $request"))))
          }
        }
      case Some(uri -> limit) if limit == 0 =>
        Future.successful(Some(None -> Right(RateLimitRetryAfter(uri, None))))
      case _ =>
        Future.successful(None)
    }).recoverWith{
      case e:Exception =>
        log.error(s"An unexpected error occured while processing requests caused by ${e.getCause}. message:  ${e.getMessage}")
        Future.failed(e)
    }
  }

  private def tooManyRequests(headers: Seq[HttpHeader], uri: Uri): Future[Option[(Option[(Uri, Int)], Either[Error, ZendeskEntity])]] = {
    headers.find(_.is("retry-after")) match {
      case Some(value) =>
        Try(Integer.parseInt(value.value())).toOption match {
          case Some(_) =>
            log.info(s"rate limit exceeded. will retry after some time")
            Future.successful(Some(None, Right(RateLimitRetryAfter(uri, None))))
          case None =>
            log.error("Invalid state. Unable to parse retry-after time after TooManyRequests aborting without finishing")
            Future.successful(Some(None -> Left(HttpClientException("Invalid state. Unable to parse Retry-after time after TooManyRequests aborting without finishing"))))
        }
      case None =>
        log.error("Invalid state. we got too many request without Retry-after time")
        Future.successful(Some(None -> Left(HttpClientException("Invalid state. we got too many request without Retry-after time"))))
    }
  }
  private def processResponse(response: HttpResponse, request: (HttpRequest, Int))(implicit executionContext: ExecutionContext):Future[Option[(Option[(Uri, Int)], Either[Error, ZendeskEntity])]]  = {
    convert(response) map { resp =>
      log.debug(s"response for request: ${request._1.uri}: data gotten ${resp}")
      if(resp._1.isDefined){
        getNextRequest(resp._1.get, request._2) match {
          case Some(uri) =>
            log.debug(s"Not done yet.. Fetching next page.. : ${uri.toString()}")
            Some(Some(uri) -> Right(resp._1.get))
          case _ =>
            log.debug(s"no next request found: end of chain")
            Some(None -> Right(resp._1.get))
        }
      }else {
        log.error("Unable to unmarshal entity to a valid type")
        Some(None -> Left(HttpClientException("Unable to unmarshal entity to a valid type")))
      }
    }
  }
  /*
  * Determine if there is a next request and return an Option[Uri]
  * @param*/
  private def getNextRequest(entity: ZendeskEntity, limit: Int): Option[(Uri, Int)] = entity match {
    case tickets: Tickets if !tickets.end_of_stream && tickets.next_page.isDefined =>
      tickets.next_page.map(Uri(_) -> (limit - 1))
    case audits: Audits =>
      audits.next_page.map(Uri(_) -> (limit - 1))
    case _ => None
  }

  /*
 * Unmarshal HttpResponse to a tuple of (Option[ZendeskEntity], Seq[Header]) given a function HttpResponse => Future[Seq[ZendeskEntity]]
 *  */
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
