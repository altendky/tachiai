FROM debian@sha256:913f6706df59a68922d1dd08f78c2476560a8d367897200a6005b00e5f67c2d5
# Fixture only; never packaged with Android or used for provider access.
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends openvpn=2.6.14-0+deb12u2 python3=3.11.2-1+b1 \
    && rm -rf /var/lib/apt/lists/*
COPY owned_server.py /owned_server.py
ENTRYPOINT ["python3", "/owned_server.py"]
