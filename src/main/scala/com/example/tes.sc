//import akka.actor.{ActorSystem, Props}
//import akka.stream.ActorMaterializer
//import akka.stream.scaladsl.Sink
//import akka.stream.scaladsl.Source
//
import java.time.{Duration, Instant, LocalDateTime, OffsetDateTime, ZoneId, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.concurrent.Await
import scala.util.{Failure, Success}
//import scala.concurrent.duration._
val d = OffsetDateTime.parse("2021-01-04T15:11:04Z", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
//val system = ActorSystem("test-system")
//val actor = system.actorOf(Props.empty,"jara")
//
//implicit val mat = ActorMaterializer()(system)
//val src = Source(Seq(Seq(1,2,3,4), Seq(5,6,7,8)))
//
//val r = src.reduce(_ ++ _).runWith(Sink.foreach(println(_)))
//
//Await.result(r, 10.second)

val time = 1609779797

val now = Instant.ofEpochSecond(OffsetDateTime.now().toEpochSecond)
//LocalDateTime.ofEpochSecond(seconds=1609779797)
val i = Instant.ofEpochSecond(1609779797)
val duration = Duration.between(i, now)
duration.abs().toMinutes