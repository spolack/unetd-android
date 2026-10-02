module org.unetd.android/libwg-go

go 1.27

// wireguard-go is a plain module dependency at upstream HEAD (CLAUDE.md).
// Upstream tags such as 0.0.20250522 are not Go semver, so the version is the
// pseudo-version of the commit; bump with
//   go get golang.zx2c4.com/wireguard@<commit> && go mod tidy
require (
	golang.org/x/sys v0.48.0
	golang.zx2c4.com/wireguard v0.0.0-20260522210424-ecfc5a8d5446
)

require (
	golang.org/x/crypto v0.57.0 // indirect
	golang.org/x/net v0.59.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
)
