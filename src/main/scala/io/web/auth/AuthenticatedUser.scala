package io.web.auth

case class AuthenticatedUser(
    uid: String,
    email: String,
    name: String,
    photo: Option[String],
    roles: Seq[String],
    dbUserId: Int
) {
    def hasRole(role: String): Boolean = roles.contains(role)
    def isAdmin: Boolean = hasRole("Administrator")
}
