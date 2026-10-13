FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -o /slateland .

FROM alpine:3.20
COPY --from=build /slateland /usr/local/bin/slateland
# The package id is written by fabric/scripts/setup.sh; wait for it before serving.
ENTRYPOINT ["sh", "-c", "while [ ! -s /ccid/slateland.ccid ]; do sleep 2; done; export CHAINCODE_ID=$(cat /ccid/slateland.ccid); exec slateland"]
