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
//#import-json-formats
//#user-routes-class
import spray.json._
class CustomerRoutes(customerRegistry: ActorRef[CustomerRegistry.Command])(implicit val system: ActorSystem[_]) {

  //#user-routes-class
//  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import JsonFormats._
  //#import-json-formats

  // If ask takes more time than this to complete the request is failed
  private implicit val timeout = Timeout.create(system.settings.config.getDuration("ticketing.routes.ask-timeout"))
//  def getUsers(): Future[Users] =
//    userRegistry.ask(GetUsers)
//  def getUser(name: String): Future[GetUserResponse] =
//    userRegistry.ask(GetUser(name, _))
  def createCustomer(customer: Customer): Future[ActionPerformed] =
    customerRegistry.ask(CreateCustomer(customer, _))

  //#all-routes
  //#users-get-post
  //#users-get-delete
  val customerRoutes: Route =
    path("customers") {
      post {
        entity(as[Customer]) { customer =>
          onSuccess(createCustomer(customer)) { performed =>
            complete(StatusCodes.Created, performed)
          }
        }
      } ~ get {

      }
    }
  //#all-routes
}
