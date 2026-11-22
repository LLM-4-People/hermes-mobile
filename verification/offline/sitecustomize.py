"""Keep the acceptance gateway's Python networking on loopback, including metadata probes."""

import ipaddress
import sys


def local(host):
    if host is None or host in ("localhost", b"localhost"):
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def restrict_network(event, args):
    if event == "socket.getaddrinfo" and not local(args[0]):
        raise OSError("External DNS disabled in mobile acceptance fixture")
    if (
        event == "socket.connect"
        and isinstance(args[1], tuple)
        and not local(args[1][0])
    ):
        raise OSError("External network disabled in mobile acceptance fixture")


sys.addaudithook(restrict_network)
