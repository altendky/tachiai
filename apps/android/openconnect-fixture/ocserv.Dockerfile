FROM debian@sha256:913f6706df59a68922d1dd08f78c2476560a8d367897200a6005b00e5f67c2d5
RUN printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ocserv=1.1.6-3 iproute2 python3 \
    && dpkg-query -W > /fixture-packages.tsv \
    && cp /usr/share/doc/ocserv/copyright /fixture-ocserv-copyright \
    && rm -rf /var/lib/apt/lists/*
COPY ocserv_owned_server.py /ocserv_owned_server.py
ENTRYPOINT ["python3", "/ocserv_owned_server.py"]
