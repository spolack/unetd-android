module org.unetd.android/libwg-go

go 1.27

require (
	golang.org/x/sys v0.48.0
	golang.zx2c4.com/wireguard v0.0.0-00010101000000-000000000000
)

require (
	golang.org/x/crypto v0.57.0 // indirect
	golang.org/x/net v0.59.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
)

// The patched checkout: see patches/wireguard-go and scripts/apply-patches.sh.
replace golang.zx2c4.com/wireguard => ../../third_party/wireguard-go
