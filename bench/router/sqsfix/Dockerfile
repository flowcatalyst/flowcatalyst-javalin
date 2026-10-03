# Purpose-built in-memory SQS fixture (bench/router/sqsfix). Stdlib only, no network at build.
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY sqsfix/ .
RUN CGO_ENABLED=0 go build -o /out/sqsfix .

FROM alpine:3.20
COPY --from=build /out/sqsfix /app/sqsfix
ENTRYPOINT ["/app/sqsfix"]
