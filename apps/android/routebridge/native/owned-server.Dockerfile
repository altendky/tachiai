FROM debian@sha256:4c25275446ca371766b8b093c696782eabce06263fccacb8d5d813b3dc44b308
# Fixture only; never packaged with Android or used for provider access.
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends openvpn=2.6.14-0+deb12u2 python3=3.11.2-1+b1 \
    && rm -rf /var/lib/apt/lists/*
COPY owned_server.py /owned_server.py
ENTRYPOINT ["python3", "/owned_server.py"]
