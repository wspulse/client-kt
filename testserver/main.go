package main

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	wspulse "github.com/wspulse/server"
	"go.uber.org/zap"
)

func main() {
	logger := zap.Must(zap.NewDevelopment())

	srv := wspulse.NewServer(
		func(r *http.Request) (roomID, connectionID string, err error) {
			if r.URL.Query().Get("reject") == "1" {
				return "", "", fmt.Errorf("rejected by test server")
			}
			room := r.URL.Query().Get("room")
			if room == "" {
				room = "test"
			}
			return room, r.URL.Query().Get("id"), nil
		},
		wspulse.WithOnMessage(func(conn wspulse.Connection, f wspulse.Frame) {
			if err := conn.Send(f); err != nil {
				logger.Warn("echo send failed", zap.Error(err))
			}
		}),
		wspulse.WithLogger(logger),
		wspulse.WithMaxMessageSize(1<<20),
	)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		logger.Fatal("listen failed", zap.Error(err))
	}

	port := ln.Addr().(*net.TCPAddr).Port
	fmt.Fprintf(os.Stderr, "READY:%d\n", port)

	go func() {
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
		<-sigCh
		logger.Info("shutting down")
		srv.Close()
		_ = ln.Close()
	}()

	if err := http.Serve(ln, srv); err != nil {
		logger.Debug("http.Serve exited", zap.Error(err))
	}
}
