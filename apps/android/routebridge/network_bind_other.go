//go:build !android

package routebridge

import "golang.zx2c4.com/wireguard/conn"

// Pinned fd accessors are Android-only. Host constructors refuse explicitly;
// owned tests inject a private fd-owning Bind without unsafe fd discovery.
func defaultNetworkBind(*boundNetwork) (conn.Bind, error) { return nil, errConfig }
