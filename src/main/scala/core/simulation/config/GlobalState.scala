package core.simulation.config

/**
 * Contains all global variables that are known at compile time and are needed
 * throughout the program
 **/
object GlobalState {
    val APP_MODE: Byte = sys.env.get("APP_MODE") match {
        case Some("debug") => AppMode.DEBUG
        case Some("server") => AppMode.SERVER
        case Some("local") => AppMode.LOCAL
        case Some("debug_server") => AppMode.DEBUG_SERVER
        case _ => AppMode.DEBUG
    }
}
