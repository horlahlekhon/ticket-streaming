package com.example

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.Timeout

import scala.util.Failure
import scala.util.Success

object Application {
  private def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit = {
    import system.executionContext

    val futureBinding = Http().newServerAt("localhost", 8081).bind(routes)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }
  def main(args: Array[String]): Unit = {
    val rootBehavior = Behaviors.setup[Nothing] { context =>
      implicit val timeout: Timeout = Timeout.create(context.system.settings.config.getDuration("ticketing.routes.ask-timeout"))
      val customerRegistryActor = context.spawn(CustomerRegistry(timeout), "CustomerRegistryActor")
      context.watch(customerRegistryActor)
      val routes = new CustomerRoutes(customerRegistryActor)(context.system)
      startHttpServer(routes.customerRoutes)(context.system)
      Behaviors.empty
    }
    ActorSystem[Nothing](rootBehavior, "ZendeskTicketStreaming")
  }
}
