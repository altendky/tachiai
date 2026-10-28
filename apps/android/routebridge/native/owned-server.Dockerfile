FROM debian@sha256:2dc419c6d7fe3dad0a2f9fd469c34cc90b0f9ba642d8fde837fe3d7d34a0e7f1
# Fixture only; never packaged with Android or used for provider access.
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends openvpn=2.6.14-0+deb12u2 python3=3.11.2-1+b1 \
    && rm -rf /var/lib/apt/lists/*
COPY owned_server.py /owned_server.py
ENTRYPOINT ["python3", "/owned_server.py"]
