package com.example

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.{ActorSystem, Dispatchers}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor}


class HttpClientSpecs extends ScalaTestWithActorTestKit(config = ConfigFactory.parseFile(new File("src/test/resources/application-test.conf")))
  with AnyWordSpecLike {

  implicit val dispatcher: Dispatchers = system.dispatchers
  implicit val ec: ExecutionContextExecutor = system.executionContext
  implicit val typedSystem: ActorSystem[Nothing] = testKit.system

  "HttpClient" should{
    "process http request until payload with end_of_stream = true " in {
      val sender= FakeHttpRequestSender(fakeType = "normal")
      val client = new HttpClient(TicketStream.converter, "", sender, system.log)
      val source = client.makeRequest(Some(Uri("https://www/google.com") -> 5))
      val future = source.runWith(Sink.seq)
      val result = Await.result(future, Duration(50, TimeUnit.SECONDS))
      result.size shouldEqual 4
      result.last match {
        case Left(value) =>
          fail("last element is a Left, but right was expected")
        case Right(value) =>
          value shouldBe a[Tickets]
          value.asInstanceOf[Tickets].end_of_stream shouldEqual true
      }
    }
    "Only make a specified amount of requests at a time" in {
      val client = new HttpClient(TicketStream.converter, "", FakeHttpRequestSender(fakeType = "normal"), system.log)
      val source = client.makeRequest(Some(Uri("https://www/google.com") -> 2))
      val future = source.runWith(Sink.seq)
      val result = Await.result(future, Duration(50, TimeUnit.SECONDS))
      result.size shouldEqual 2
      result.last match {
        case Left(_) =>
          fail("Last element is left but right was expected")
        case Right(value) if value.isInstanceOf[RateLimitRetryAfter] =>
          val rateLimitRetryAfter = value.asInstanceOf[RateLimitRetryAfter]
          rateLimitRetryAfter.data.size shouldEqual 0
          rateLimitRetryAfter.currentPageData shouldEqual None
        case ee =>
          fail("Invalid response ")
      }
    }
    "Handle rate limit error gracefully" in {
      val fakeHttpRequestSender =  FakeHttpRequestSender("limit")
      val client = new HttpClient(TicketStream.converter, "", fakeHttpRequestSender, system.log)
      val source = client.makeRequest(Some(Uri("https://www/google.com") -> 10))
      val future = source.runWith(Sink.seq)
      val result = Await.result(future, Duration(50, TimeUnit.SECONDS))
      result.last match {
        case Left(value) =>
          fail("Right expcted, got Left")
        case Right(value) if value.isInstanceOf[RateLimitRetryAfter] =>
          val rateLimitRetryAfter = value.asInstanceOf[RateLimitRetryAfter]
          rateLimitRetryAfter.data.size shouldEqual 0
          rateLimitRetryAfter.currentPageData shouldEqual None
        case ee =>
          fail("Invalid response ")
      }
    }

  }
}
