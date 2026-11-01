"""Private loopback CONNECT settings for the explicit development route trial."""

import base64
from dataclasses import dataclass
import http.client
import re
import ssl
import urllib.parse
import urllib.request


class RouteRefused(Exception):
    pass


@dataclass(frozen=True, repr=False)
class LoopbackRoute:
    port: int
    username: str
    password: str
    realm: str

    def __repr__(self):
        return "LoopbackRoute(redacted)"

    def private_packet(self):
        return {"kind": "loopback-connect", "host": "127.0.0.1", "port": self.port,
                "username": self.username, "password": self.password, "realm": self.realm}


def validate_route(value):
    if value is None:
        return None
    if isinstance(value, LoopbackRoute):
        value = value.private_packet()
    if type(value) is not dict or set(value) != {"kind", "host", "port", "username", "password", "realm"}:
        raise RouteRefused()
    if value["kind"] != "loopback-connect" or value["host"] != "127.0.0.1" or type(value["port"]) is not int or not 1 <= value["port"] <= 65535:
        raise RouteRefused()
    if any(type(value[key]) is not str or not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", value[key]) for key in ("username", "password", "realm")):
        raise RouteRefused()
    return LoopbackRoute(value["port"], value["username"], value["password"], value["realm"])


class _RejectHTTP(urllib.request.HTTPHandler):
    def http_open(self, request):
        raise RouteRefused()


class _ConnectHTTPS(urllib.request.HTTPSHandler):
    def __init__(self, route):
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        self.context.load_default_certs()
        super().__init__(context=self.context)
        self.route = route

    def https_open(self, request):
        # Never use Request.set_proxy/ProxyHandler.proxy_open: that path can
        # silently honour NO_PROXY even when its proxy was supplied explicitly.
        if any(name.lower() == "proxy-authorization" for name, _ in request.header_items()):
            raise RouteRefused()

        def connection(authority, timeout=10):
            try:
                target = urllib.parse.urlsplit("https://" + authority)
                if target.username is not None or target.password is not None or target.path or target.query or target.fragment or target.port not in (None, 443):
                    raise RouteRefused()
                host = target.hostname
                if not host or authority != host and authority != host + ":443" or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", host):
                    raise RouteRefused()
                token = base64.b64encode((self.route.username + ":" + self.route.password).encode("ascii")).decode("ascii")
                # HTTPSConnection performs normal origin TLS validation after
                # CONNECT. Proxy credentials exist only in the tunnel headers.
                # create_default_context may honour SSLKEYLOGFILE. This private
                # diagnostic must not export TLS secrets through the environment.
                result = http.client.HTTPSConnection("127.0.0.1", self.route.port, timeout=timeout, context=self.context)
                result.set_tunnel(host, 443, headers={"Proxy-Authorization": "Basic " + token})
                return result
            except (ValueError, UnicodeError):
                raise RouteRefused() from None

        return self.do_open(connection, request)


def make_opener(route, no_redirect):
    route = validate_route(route)
    handlers = [urllib.request.ProxyHandler({}), no_redirect]
    if route is not None:
        handlers += [_ConnectHTTPS(route), _RejectHTTP()]
    return urllib.request.build_opener(*handlers)
