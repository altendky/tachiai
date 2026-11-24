FROM debian@sha256:3cda3ca472ce45507a5109c6a3907093e24d9ae67d55954c9a07a45665dafba2
# Fixture only; never packaged with Android or used for provider access.
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends openvpn=2.6.14-0+deb12u2 python3=3.11.2-1+b1 \
    && rm -rf /var/lib/apt/lists/*
COPY owned_server.py /owned_server.py
ENTRYPOINT ["python3", "/owned_server.py"]
