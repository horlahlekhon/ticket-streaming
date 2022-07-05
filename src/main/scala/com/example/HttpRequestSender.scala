package com.example

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpHeader, HttpMethods, HttpRequest, HttpResponse, Uri}

import scala.concurrent.Future

trait HttpRequestSender {
  def sendRequest(request: HttpRequest): Future[HttpResponse]

}

class HttpServer()(implicit system: ActorSystem[_]) extends HttpRequestSender {
  override def sendRequest(request: HttpRequest): Future[HttpResponse] = {
    Http().singleRequest(request)
  }
}
object HttpServer {
  def apply()(implicit system: ActorSystem[_]): HttpRequestSender = {
    new HttpServer()
  }
}