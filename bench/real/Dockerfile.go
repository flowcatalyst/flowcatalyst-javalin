# Go's fc-server, statically built for linux/arm64 (CGO_ENABLED=0).
FROM alpine:3.20
COPY fc-server-linux /app/fc-server
ENTRYPOINT ["/app/fc-server"]
