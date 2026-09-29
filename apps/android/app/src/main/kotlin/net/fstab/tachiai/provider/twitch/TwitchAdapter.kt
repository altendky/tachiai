package net.fstab.tachiai.provider.twitch

import java.net.URI
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.ProviderAdapter
import net.fstab.tachiai.provider.isExactHttpsOrigin

object TwitchAdapter : ProviderAdapter {
    const val CHANNEL = "midnightsumo"
    const val ASSET_HOST = "appassets.androidplatform.net"
    const val ASSET_URL = "https://$ASSET_HOST/assets/twitch/player.html"
    const val FULL_SITE_HOST = "www.twitch.tv"
    const val MOBILE_SITE_HOST = "m.twitch.tv"
    private val channelPattern = Regex("[A-Za-z0-9_]{3,25}")
    private val reservedFullSitePaths = setOf(
        "directory",
        "downloads",
        "login",
        "settings",
        "signup",
        "store",
        "subscriptions",
        "turbo",
        "wallet",
    )

    override val id = ProviderId("twitch")
    override val displayName = "Twitch"

    override fun browserRequest(resource: ResourceLocator): BrowserRequest {
        val uri = runCatching { URI(resource.value) }.getOrNull()
        if (uri != null && isFullSiteChannelUri(uri)) {
            return BrowserRequest(url = resource.value, acceptsThirdPartyCookies = true)
        }
        require(channelPattern.matches(resource.value)) { "Invalid Twitch channel name." }
        return BrowserRequest(
            url = "$ASSET_URL?channel=${resource.value}",
            isPackagedAsset = true,
            acceptsThirdPartyCookies = true,
        )
    }

    override fun isTopLevelNavigationAllowed(uri: URI): Boolean =
        isPackagedPlayerUri(uri) ||
            uri.isExactHttpsOrigin(FULL_SITE_HOST) ||
            uri.isExactHttpsOrigin(MOBILE_SITE_HOST)

    override fun isProtectedMediaOriginAllowed(uri: URI): Boolean =
        uri.isExactHttpsOrigin("player.twitch.tv")

    override fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String? {
        val commandName = command.name.lowercase()
        if (isPackagedPlayerUri(requestedUri) && currentUri == requestedUri) {
            return "window.tachiaiPlayerCommand('$commandName')"
        }
        if (!isSameFullSiteChannel(requestedUri, currentUri)) return null
        return """
            (() => {
              const media = Array.from(document.querySelectorAll('video'))
                .filter(candidate => {
                  const bounds = candidate.getBoundingClientRect();
                  const computed = getComputedStyle(candidate);
                  return candidate.isConnected && bounds.width > 0 && bounds.height > 0 &&
                    computed.display !== 'none' && computed.visibility !== 'hidden';
                })
                .sort((left, right) => {
                  const leftBounds = left.getBoundingClientRect();
                  const rightBounds = right.getBoundingClientRect();
                  return rightBounds.width * rightBounds.height -
                    leftBounds.width * leftBounds.height;
                })[0];
              if (!media) return 'unavailable';
              switch ('$commandName') {
                case 'play':
                  media.play().catch(() => {});
                  return 'play requested';
                case 'pause':
                  media.pause();
                  return media.paused ? 'paused' : 'unavailable';
                case 'mute':
                  media.muted = true;
                  media.dispatchEvent(new Event('volumechange', { bubbles: true }));
                  return media.muted ? 'muted' : 'unavailable';
                case 'unmute':
                  media.muted = false;
                  if (media.volume === 0) media.volume = 0.5;
                  media.dispatchEvent(new Event('volumechange', { bubbles: true }));
                  return media.muted || media.volume === 0 ? 'unavailable' : 'unmuted';
                case 'volume_down':
                  media.volume = Math.max(0, Math.round((media.volume - 0.1) * 10) / 10);
                  media.dispatchEvent(new Event('volumechange', { bubbles: true }));
                  return `volume ${'$'}{Math.round(media.volume * 100)}%`;
                case 'volume_up':
                  media.volume = Math.min(1, Math.round((media.volume + 0.1) * 10) / 10);
                  media.dispatchEvent(new Event('volumechange', { bubbles: true }));
                  return `volume ${'$'}{Math.round(media.volume * 100)}%`;
                default:
                  return 'unsupported';
              }
            })()
        """.trimIndent()
    }

    override fun focusScriptFor(requestedUri: URI, currentUri: URI): String? {
        if (!isSameFullSiteChannel(requestedUri, currentUri)) return null
        val expectedHost = currentUri.host
        val expectedPath = requestedUri.path
        val expectedHomePath = "${requestedUri.path}/home"
        return """
            (() => {
              window.__tachiaiFocusCleanup?.();
              const expectedHost = '$expectedHost';
              const expectedPath = '$expectedPath';
              const expectedHomePath = '$expectedHomePath';
              const ownedStyles = [];
              let activeRoot = null;
              const recordFor = (element, property) =>
                ownedStyles.find(record =>
                  record.element === element && record.property === property
                );
              const setOwnedStyles = (element, properties) => {
                if (!element) return;
                Object.entries(properties).forEach(([property, value]) => {
                  let record = recordFor(element, property);
                  if (!record) {
                    record = {
                      element,
                      property,
                      originalValue: element.style.getPropertyValue(property),
                      originalPriority: element.style.getPropertyPriority(property),
                      appliedValue: '',
                      appliedPriority: ''
                    };
                    ownedStyles.push(record);
                  }
                  element.style.setProperty(property, value);
                  record.appliedValue = element.style.getPropertyValue(property);
                  record.appliedPriority = element.style.getPropertyPriority(property);
                });
              };
              const clearOwnedStyles = () => {
                ownedStyles.reverse().forEach(record => {
                  const currentValue = record.element.style.getPropertyValue(record.property);
                  const currentPriority = record.element.style.getPropertyPriority(record.property);
                  if (currentValue !== record.appliedValue ||
                      currentPriority !== record.appliedPriority) return;
                  if (record.originalValue) {
                    record.element.style.setProperty(
                      record.property,
                      record.originalValue,
                      record.originalPriority
                    );
                  } else {
                    record.element.style.removeProperty(record.property);
                  }
                });
                ownedStyles.length = 0;
                activeRoot = null;
              };
              let observer;
              let timer;
              const cleanup = () => {
                observer?.disconnect();
                clearInterval(timer);
                clearOwnedStyles();
                delete window.__tachiaiFocusCleanup;
              };
              const visibleVideo = () => Array.from(document.querySelectorAll('video'))
                .filter(video => {
                  if (!video.isConnected) return false;
                  const computed = getComputedStyle(video);
                  const bounds = video.getBoundingClientRect();
                  return computed.display !== 'none' &&
                    computed.visibility !== 'hidden' &&
                    bounds.width > 0 && bounds.height > 0;
                })
                .sort((left, right) => {
                  const leftBounds = left.getBoundingClientRect();
                  const rightBounds = right.getBoundingClientRect();
                  return rightBounds.width * rightBounds.height -
                    leftBounds.width * leftBounds.height;
                })[0];
              const dismissWebHandoff = () => {
                const button = Array.from(document.querySelectorAll('button'))
                  .find(candidate => candidate.textContent?.trim() === 'Keep using web');
                if (!button) return false;
                const buttonBounds = button.getBoundingClientRect();
                if (buttonBounds.width <= 0 || buttonBounds.height <= 0) return false;
                const prompt = button.parentElement;
                const appLink = Array.from(prompt?.querySelectorAll('a') || [])
                  .find(candidate => candidate.textContent?.trim() === 'Open in App');
                if (!appLink) return false;
                let appUri;
                try {
                  appUri = new URL(appLink.href, location.href);
                } catch (_) {
                  return false;
                }
                const appHostAllowed = appUri.hostname === 'twitch.tv' ||
                  appUri.hostname === 'www.twitch.tv' ||
                  appUri.hostname === 'm.twitch.tv';
                const appPathMatches = appUri.pathname.toLowerCase() ===
                  expectedPath.toLowerCase();
                if (appUri.protocol !== 'https:' || !appHostAllowed ||
                    !appPathMatches || appUri.search || appUri.hash) return false;
                button.click();
                return true;
              };
              const focus = () => {
                const onExpectedRoute = location.protocol === 'https:' &&
                  location.hostname === expectedHost &&
                  (location.pathname === expectedPath ||
                    location.pathname === expectedHomePath) &&
                  location.search === '' && location.hash === '';
                if (!onExpectedRoute) {
                  clearOwnedStyles();
                  return false;
                }
                if (dismissWebHandoff()) return false;
                if (document.querySelector('.ReactModal__Overlay')) {
                  clearOwnedStyles();
                  return false;
                }
                const video = visibleVideo();
                if (!video) return false;
                const container = video.closest('[class*="playerContainerMWeb"]') ||
                  video.closest('[class*="video-player__"]') || video.parentElement;
                const root = video.closest('[class*="ScAspectRatio"]') || container;
                if (!container || !root) return false;
                if (activeRoot && activeRoot !== root) clearOwnedStyles();
                activeRoot = root;
                const videoPlayer = video.closest('[class*="video-player__"]') ||
                  container.querySelector('[class*="video-player__"]');
                const videoReference = video.closest('[class*="video-ref"]') ||
                  container.querySelector('[class*="video-ref"]');
                const advertisementWrapper =
                  video.closest('[class*="stream-display-ad__wrapper"]') ||
                  container.querySelector('[class*="stream-display-ad__wrapper"]');
                setOwnedStyles(root, {
                  'position': 'fixed',
                  'inset': '0',
                  'width': '100vw',
                  'height': '100vh',
                  'max-width': 'none',
                  'max-height': 'none',
                  'overflow': 'visible',
                  'padding': '0',
                  'transform': 'none',
                  'background': 'black',
                  'z-index': '2147483647'
                });
                if (container !== root) {
                  setOwnedStyles(container, {
                    'position': 'absolute',
                    'inset': '0',
                    'width': '100%',
                    'height': '100%',
                    'max-width': 'none',
                    'max-height': 'none',
                    'overflow': 'visible',
                    'transform': 'none'
                  });
                }
                [...new Set([advertisementWrapper, videoPlayer, videoReference])]
                  .filter(Boolean)
                  .forEach(element => setOwnedStyles(element, {
                    'position': 'absolute',
                    'inset': '0',
                    'width': '100%',
                    'height': '100%',
                    'max-width': 'none',
                    'max-height': 'none',
                    'transform': 'none'
                  }));
                setOwnedStyles(video, {
                  'position': 'absolute',
                  'inset': '0',
                  'width': '100%',
                  'height': '100%',
                  'max-width': 'none',
                  'max-height': 'none',
                  'object-fit': 'contain',
                  'background': 'black',
                  'transform': 'none'
                });
                return true;
              };
              observer = new MutationObserver(focus);
              observer.observe(document.documentElement, { childList: true, subtree: true });
              timer = setInterval(focus, 1000);
              window.__tachiaiFocusCleanup = cleanup;
              focus();
              return 'focus installed';
            })()
        """.trimIndent()
    }

    fun fullSiteResource(channel: String): ResourceLocator {
        require(channelPattern.matches(channel)) { "Invalid Twitch channel name." }
        return ResourceLocator("https://$MOBILE_SITE_HOST/$channel")
    }

    private fun isPackagedPlayerUri(uri: URI): Boolean =
        uri.isExactHttpsOrigin(ASSET_HOST) &&
            uri.path == "/assets/twitch/player.html" &&
            (uri.rawQuery == null || uri.rawQuery.matches(Regex("channel=[A-Za-z0-9_]{3,25}")))

    private fun isFullSiteChannelUri(uri: URI): Boolean =
        (uri.isExactHttpsOrigin(FULL_SITE_HOST) || uri.isExactHttpsOrigin(MOBILE_SITE_HOST)) &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.path.removePrefix("/").let { path ->
                path.matches(channelPattern) && path.lowercase() !in reservedFullSitePaths
            }

    private fun isSameFullSiteChannel(requestedUri: URI, currentUri: URI): Boolean =
        isFullSiteChannelUri(requestedUri) &&
            (currentUri.isExactHttpsOrigin(FULL_SITE_HOST) ||
                currentUri.isExactHttpsOrigin(MOBILE_SITE_HOST)) &&
            currentUri.rawQuery == null &&
            currentUri.rawFragment == null &&
            (
                requestedUri.path.equals(currentUri.path, ignoreCase = true) ||
                    "${requestedUri.path}/home".equals(currentUri.path, ignoreCase = true)
                )
}
