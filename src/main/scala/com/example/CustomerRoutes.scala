package com.example

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route

import scala.concurrent.Future
import com.example.CustomerRegistry._
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

class CustomerRoutes(customerRegistry: ActorRef[CustomerRegistry.Command])(implicit val system: ActorSystem[_]) {

  import JsonFormats._

  // If ask takes more time than this to complete the request is failed
  private implicit val timeout = Timeout.create(system.settings.config.getDuration("ticketing.routes.ask-timeout"))
  def createCustomer(customer: Customer): Future[ActionPerformed] =
    customerRegistry.ask(CreateCustomer(customer, _))

  def getCurrentTimeLapse(domain: String): Future[ActionPerformed] =
    customerRegistry.ask(GetCurrentTimeLapse(domain, _))
  val customerRoutes: Route =
    path("customers") {
      post {
        entity(as[Customer]) { customer =>
          onSuccess(createCustomer(customer)) { performed =>
            complete(StatusCodes.Created, performed)
          }
        }
      }
    } ~ (path("customers" / Segment) & get){ domain =>
      complete{
        getCurrentTimeLapse(domain)
      }
    }
}
