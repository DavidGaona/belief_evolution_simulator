package io.web.auth

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.*
import com.google.firebase.auth.FirebaseAuth
import io.db.DatabaseManager
import io.web.auth.ErrorResponses.*
import utils.logging.Logger

object FirebaseAuthDirective {

    // Verifies the Bearer token, loads roles from DB, and provides AuthenticatedUser.
    // Returns 401 if the token is missing/invalid, or if the user is deactivated.
    val authenticate: Directive1[AuthenticatedUser] =
        optionalHeaderValueByName("Authorization").flatMap {
            case Some(header) if header.startsWith("Bearer ") =>
                val idToken = header.stripPrefix("Bearer ").trim
                scala.util.Try(FirebaseAuth.getInstance().verifyIdToken(idToken)) match {
                    case scala.util.Success(token) =>
                        val uid   = token.getUid
                        val email = Option(token.getEmail).getOrElse("")
                        val name  = Option(token.getName).getOrElse("")
                        val photo = Option(token.getPicture)

                        DatabaseManager.createOrUpdateUser(uid, email, name, photo) match {
                            case Some(user) if user.deactivated =>
                                complete(StatusCodes.Unauthorized ->
                                    errorJson("unauthorized", "Account is deactivated"))
                            case Some(user) =>
                                val bootstrapEmails = sys.env
                                    .getOrElse("BOOTSTRAP_ADMIN_EMAILS", "")
                                    .split(",")
                                    .map(_.trim)
                                    .filter(_.nonEmpty)

                                val isAdmin = user.roles.contains("Administrator")
                                if (bootstrapEmails.contains(email) && !isAdmin) {
                                    DatabaseManager.addUserRole(uid, "Administrator")
                                    Logger.log(s"Bootstrap admin promoted: $email")
                                }

                                val finalRoles =
                                    if (bootstrapEmails.contains(email) && !isAdmin)
                                        user.roles :+ "Administrator"
                                    else
                                        user.roles

                                provide(AuthenticatedUser(
                                    uid      = uid,
                                    email    = email,
                                    name     = name,
                                    photo    = photo,
                                    roles    = finalRoles,
                                    dbUserId = user.id
                                ))
                            case None =>
                                complete(StatusCodes.InternalServerError ->
                                    errorJson("internal_error", "Failed to resolve user"))
                        }

                    case scala.util.Failure(ex) =>
                        Logger.log(s"Token verification failed: ${ex.getMessage}")
                        complete(StatusCodes.Unauthorized ->
                            errorJson("unauthorized", "Invalid or expired token"))
                }

            case _ =>
                complete(StatusCodes.Unauthorized ->
                    errorJson("unauthorized", "Missing Bearer token"))
        }

    // Shortcut: authenticate + require Administrator role
    val requireAdmin: Directive1[AuthenticatedUser] =
        authenticate.flatMap { user =>
            if (user.isAdmin) provide(user)
            else complete(StatusCodes.Forbidden ->
                errorJson("forbidden", "Administrator role required"))
        }
}