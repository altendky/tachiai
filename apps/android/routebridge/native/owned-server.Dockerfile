FROM debian@sha256:9c6e381421d0f48d1b8133e573f33c185ee16cbf505eda9c4a8a4791b25ea8a6
# Fixture only; never packaged with Android or used for provider access.
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends openvpn=2.6.14-0+deb12u2 python3=3.11.2-1+b1 \
    && rm -rf /var/lib/apt/lists/*
COPY owned_server.py /owned_server.py
ENTRYPOINT ["python3", "/owned_server.py"]
