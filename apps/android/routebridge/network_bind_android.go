//go:build android

package routebridge

import "golang.zx2c4.com/wireguard/conn"

func defaultNetworkBind(network *boundNetwork) (conn.Bind, error) {
	inner := conn.NewDefaultBind()
	peek, ok := inner.(conn.PeekLookAtSocketFd)
	if !ok {
		return nil, errConfig
	}
	return &networkBind{inner: inner, network: network, fds: func() (fds []int, err error) {
		// Pinned helpers have no explicit absent-family guard. A missing family
		// is refused (including a nil-receiver panic), never left unbound.
		defer func() {
			if recover() != nil {
				fds = nil
				err = errConnect
			}
		}()
		v4, err := peek.PeekLookAtSocketFd4()
		if err != nil {
			return nil, errConnect
		}
		v6, err := peek.PeekLookAtSocketFd6()
		if err != nil {
			return nil, errConnect
		}
		return []int{v4, v6}, nil
	}}, nil
}
