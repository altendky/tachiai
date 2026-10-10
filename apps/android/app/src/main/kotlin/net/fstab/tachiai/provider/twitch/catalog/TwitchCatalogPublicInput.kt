package net.fstab.tachiai.provider.twitch.catalog

import java.net.URI
import java.util.Locale

internal fun validTwitchCatalogId(value: String) = value.matches(Regex("[1-9][0-9]{0,31}"))
internal fun validTwitchCatalogLogin(value: String) = value.matches(Regex("[a-z0-9_]{1,64}"))
internal fun validTwitchCatalogCursor(value: String) = value.length in 1..2048 &&
    value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

internal sealed interface TwitchCatalogPublicInput {
    class BroadcasterId(val id: String) : TwitchCatalogPublicInput {
        init { require(validTwitchCatalogId(id)) }
        override fun toString() = "TwitchCatalogPublicInput.BroadcasterId(redacted)"
    }
    class Login(val login: String) : TwitchCatalogPublicInput {
        init { require(validTwitchCatalogLogin(login)) }
        override fun toString() = "TwitchCatalogPublicInput.Login(redacted)"
    }
    class VideoId(val id: String) : TwitchCatalogPublicInput {
        init { require(validTwitchCatalogId(id)) }
        override fun toString() = "TwitchCatalogPublicInput.VideoId(redacted)"
    }
}

private val reservedTwitchRoutes = setOf("directory", "videos", "settings", "downloads", "login", "signup",
    "subscriptions", "inventory", "wallet", "search", "p", "jobs", "turbo", "prime", "friends", "messages",
    "moderator", "broadcast", "creatorcamp")

// URLs are input aliases only. A Login must be verified with Get Users before
// canonical Add; neither a current URL nor a recycled login is an account ID.
internal fun parseTwitchCatalogPublicInput(input: String): TwitchCatalogPublicInput {
    fun invalid(): Nothing = throw TwitchHelixException(TwitchHelixFailure.INVALID_INPUT)
    if (input.length !in 1..2048 || input.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) invalid()
    val value = input.trim()
    if (validTwitchCatalogId(value)) return TwitchCatalogPublicInput.BroadcasterId(value)
    if (value.isNotEmpty() && value.all(Char::isDigit)) invalid()
    val login = value.lowercase(Locale.ROOT)
    if (validTwitchCatalogLogin(login)) return TwitchCatalogPublicInput.Login(login)
    val uri = try { URI(value) } catch (_: Exception) { invalid() }
    if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) !in setOf("twitch.tv", "www.twitch.tv") ||
        uri.port !in listOf(-1, 443) || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        uri.rawPath != uri.path) invalid()
    val video = Regex("/videos/([1-9][0-9]{0,31})/?").matchEntire(uri.rawPath)?.groupValues?.get(1)
    if (video != null) return TwitchCatalogPublicInput.VideoId(video)
    val channel = Regex("/([A-Za-z0-9_]{1,64})/?").matchEntire(uri.rawPath)?.groupValues?.get(1)
        ?.lowercase(Locale.ROOT) ?: invalid()
    if (channel in reservedTwitchRoutes) invalid()
    return TwitchCatalogPublicInput.Login(channel)
}
