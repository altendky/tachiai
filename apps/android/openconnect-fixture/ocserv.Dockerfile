FROM debian@sha256:9c6e381421d0f48d1b8133e573f33c185ee16cbf505eda9c4a8a4791b25ea8a6
RUN printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ocserv=1.1.6-3 iproute2 python3 \
    && dpkg-query -W > /fixture-packages.tsv \
    && cp /usr/share/doc/ocserv/copyright /fixture-ocserv-copyright \
    && rm -rf /var/lib/apt/lists/*
COPY ocserv_owned_server.py /ocserv_owned_server.py
ENTRYPOINT ["python3", "/ocserv_owned_server.py"]
