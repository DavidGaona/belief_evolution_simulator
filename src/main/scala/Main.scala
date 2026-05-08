import akka.actor.{ActorSystem, Props}
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.{FirebaseApp, FirebaseOptions}
import com.typesafe.config.ConfigFactory
import core.simulation.actors.Monitor
import io.web.Server
import utils.logging.Logger

import java.lang
import scala.reflect

object Main extends App {
    val maxMemory = Runtime.getRuntime.maxMemory() / (1024 * 1024)
    Logger.log(s"Max memory: $maxMemory")

    initFirebase()

    val system = ActorSystem("original", ConfigFactory.load().getConfig("app-dispatcher"))
    val monitor = system.actorOf(Props(new Monitor), "Monitor")
    Server.initialize(system, monitor)

    private def initFirebase(): Unit = {
        scala.util.Try {
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault)
                .build()
            FirebaseApp.initializeApp(options)
            Logger.log("Firebase initialized successfully")
        }.recover { case ex =>
            Logger.log(s"Firebase initialization failed (auth endpoints will return 500): ${ex.getMessage}")
        }
    }
}
