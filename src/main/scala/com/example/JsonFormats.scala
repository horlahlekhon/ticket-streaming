package com.example

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.example.CustomerRegistry.ActionPerformed
import spray.json.{JsString, JsValue, JsonFormat, RootJsonFormat, deserializationError}

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import scala.util.Try

import spray.json.DefaultJsonProtocol

object JsonFormats extends DefaultJsonProtocol with SprayJsonSupport {

  implicit val customerJsonFormat = jsonFormat3(Customer)

  implicit val actionPerformedJsonFormat = jsonFormat2(ActionPerformed)

  implicit val badCredentialsJsonFormat = jsonFormat1(BadCredentials)


  implicit val localDateTimeFormat =  new JsonFormat[OffsetDateTime] {
    override def write(obj: OffsetDateTime): JsValue = JsString(obj.toString)
    private val deserializationErrorMessage =
      s"Expected date time in ISO offset date time format ex. ${OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}"
    override def read(json: JsValue): OffsetDateTime = {
      json match {
        case JsString(value) =>
          Try(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)).getOrElse(deserializationError(deserializationErrorMessage))
        case _ => deserializationError(deserializationErrorMessage)
      }
    }
  }
  implicit val auditJsonFormat: RootJsonFormat[Audit] = jsonFormat3(Audit)
  implicit val auditsJsonFormats: RootJsonFormat[Audits] = jsonFormat3(Audits)

  implicit val ticketJsonFormats: RootJsonFormat[Ticket] = jsonFormat3(Ticket)
  implicit val ticketsJsonFormats: RootJsonFormat[Tickets] = jsonFormat5(Tickets)

  implicit val auditCountFormat: RootJsonFormat[AuditCount] = jsonFormat1(AuditCount)

  implicit object ZendeskEntityFormat extends RootJsonFormat[ZendeskEntity] {
    override def write(obj: ZendeskEntity): JsValue = ???

    override def read(json: JsValue): ZendeskEntity = {
      val fields = json.asJsObject.fields
      if(fields.contains("tickets")) {
        json.convertTo[Tickets]
      }else if(fields.contains("audits")) {
        json.convertTo[Audits]
      }else{
        deserializationError("Invalid response body")
      }
    }
  }
}
