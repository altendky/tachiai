FROM debian@sha256:8c4733a92aacd0bcff3779eadc35a4a5147f36cb20a5fcb56504cbc740212259
RUN printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ocserv=1.1.6-3 iproute2 python3 \
    && dpkg-query -W > /fixture-packages.tsv \
    && cp /usr/share/doc/ocserv/copyright /fixture-ocserv-copyright \
    && rm -rf /var/lib/apt/lists/*
COPY ocserv_owned_server.py /ocserv_owned_server.py
ENTRYPOINT ["python3", "/ocserv_owned_server.py"]
