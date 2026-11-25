//go:build android

package tun

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"golang.org/x/sys/unix"
)

// The socket lives under Android's app-private cache directory. No app packets
// traverse it: it transfers a single descriptor and an acknowledgement.
func receiveAndroidTun(path string) (int, *net.UnixConn, error) {
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return -1, nil, err
	}
	defer listener.Close()
	deadline := time.Now().Add(4 * time.Second)
	if err = listener.SetDeadline(deadline); err != nil {
		return -1, nil, err
	}
	conn, err := listener.AcceptUnix()
	if err != nil {
		return -1, nil, err
	}
	ok := false
	defer func() {
		if !ok {
			conn.Close()
		}
	}()
	if err = conn.SetDeadline(deadline); err != nil {
		return -1, nil, err
	}
	raw, err := conn.SyscallConn()
	if err != nil {
		return -1, nil, err
	}
	var peer *unix.Ucred
	var credErr error
	err = raw.Control(func(fd uintptr) {
		peer, credErr = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
	})
	if err != nil || credErr != nil || peer == nil || int(peer.Uid) != unix.Getuid() {
		return -1, nil, fmt.Errorf("Android TUN sender is not the app UID")
	}
	var marker [1]byte
	oob := make([]byte, unix.CmsgSpace(4*4))
	n, oobn, flags, _, err := conn.ReadMsgUnix(marker[:], oob)
	if err != nil {
		return -1, nil, err
	}
	messages, err := unix.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return -1, nil, err
	}
	var fds []int
	for _, message := range messages {
		if message.Header.Level == unix.SOL_SOCKET && message.Header.Type == unix.SCM_RIGHTS {
			got, parseErr := unix.ParseUnixRights(&message)
			if parseErr == nil {
				fds = append(fds, got...)
			}
		}
	}
	if n != 1 || marker[0] != 'T' || len(fds) != 1 || flags&unix.MSG_CTRUNC != 0 || fds[0] <= 2 {
		for _, fd := range fds {
			unix.Close(fd)
		}
		return -1, nil, fmt.Errorf("expected exactly one Android TUN descriptor")
	}
	unix.CloseOnExec(fds[0])
	ok = true
	return fds[0], conn, nil
}

func serveAndroidHealth(ctx context.Context, conn net.Conn) {
	defer conn.Close()
	if conn.SetDeadline(time.Time{}) != nil {
		return
	}
	manager := service.FromContext[adapter.OutboundManager](ctx)
	if manager == nil {
		return
	}
	node, exists := manager.Outbound("node")
	if !exists {
		return
	}
	// Keep reading the control socket while HTTPS is in flight so EOF (or an
	// invalid command) can stop the engine without waiting on the node.
	commands := make(chan struct{}, 1)
	defer close(commands)
	go probeAndroidHealth(ctx, conn, node, commands)
	var command [1]byte
	for {
		if _, err := io.ReadFull(conn, command[:]); err != nil || command[0] != 'Q' {
			return
		}
		select {
		case commands <- struct{}{}:
		default:
			return
		}
	}
}

func probeAndroidHealth(ctx context.Context, conn net.Conn, node adapter.Outbound, commands <-chan struct{}) {
	defer conn.Close() // Wake the control reader if writing the answer fails.
	transport := &http.Transport{DisableKeepAlives: true, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		return node.DialContext(ctx, network, M.ParseSocksaddr(address))
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 4 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	for range commands {
		answer := byte('F')
		request, err := http.NewRequestWithContext(ctx, "GET", "https://speed.cloudflare.com/__down?bytes=64", nil)
		if err == nil {
			response, requestErr := client.Do(request)
			if requestErr == nil {
				var body [64]byte
				if _, readErr := io.ReadFull(response.Body, body[:]); response.StatusCode == 200 && readErr == nil {
					answer = 'Y'
				}
				response.Body.Close()
			}
		}
		if _, err = conn.Write([]byte{answer}); err != nil {
			return
		}
	}
}
