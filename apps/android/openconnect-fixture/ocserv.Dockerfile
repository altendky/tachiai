FROM debian@sha256:3cda3ca472ce45507a5109c6a3907093e24d9ae67d55954c9a07a45665dafba2
RUN printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ocserv=1.1.6-3 iproute2 python3 \
    && dpkg-query -W > /fixture-packages.tsv \
    && cp /usr/share/doc/ocserv/copyright /fixture-ocserv-copyright \
    && rm -rf /var/lib/apt/lists/*
COPY ocserv_owned_server.py /ocserv_owned_server.py
ENTRYPOINT ["python3", "/ocserv_owned_server.py"]
