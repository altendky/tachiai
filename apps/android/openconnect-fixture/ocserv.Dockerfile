FROM debian@sha256:2dc419c6d7fe3dad0a2f9fd469c34cc90b0f9ba642d8fde837fe3d7d34a0e7f1
RUN printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ocserv=1.1.6-3 iproute2 python3 \
    && dpkg-query -W > /fixture-packages.tsv \
    && cp /usr/share/doc/ocserv/copyright /fixture-ocserv-copyright \
    && rm -rf /var/lib/apt/lists/*
COPY ocserv_owned_server.py /ocserv_owned_server.py
ENTRYPOINT ["python3", "/ocserv_owned_server.py"]
