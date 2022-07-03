package com.example

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import scala.util.Failure
import scala.util.Success

//#main-class
object Application {
  //#start-http-server
  private def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit = {
    import system.executionContext

    val futureBinding = Http().newServerAt("localhost", 8080).bind(routes)
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
    //#server-bootstrapping

    val rootBehavior = Behaviors.setup[Nothing] { context =>
      val baseUrl =  context.system.settings.config.getString("ticketing.app.base-url")
      val customerRegistryActor = context.spawn(CustomerRegistry(baseUrl), "UserRegistryActor")
      context.watch(customerRegistryActor)
      val routes = new CustomerRoutes(customerRegistryActor)(context.system)
      startHttpServer(routes.customerRoutes)(context.system)
      Behaviors.empty
    }
    val system = ActorSystem[Nothing](rootBehavior, "ZendeskTicketStreaming")
    //#server-bootstrapping
  }
}
//#main-class
