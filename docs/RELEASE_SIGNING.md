# Fork release signing

Release APKs from aminsh35322088-ctrl/WhiteDNS-Android use a dedicated signing certificate. Its public certificate is `release-signing-cert.pem`.

SHA-256 certificate fingerprint:
`92:57:D5:2B:AE:28:35:F4:B8:0C:E8:9F:A9:F9:89:73:8D:B3:71:F1:48:58:1E:0D:80:8B:7B:74:7A:E3:0A:B6`

The signing keystore and passwords are stored as encrypted GitHub Actions repository secrets. Preserve them for all subsequent releases: replacing the key prevents ordinary upgrades of existing fork installations.

This certificate differs from upstream WhiteDNS's signing certificate. A fork release cannot be installed as an update over an upstream-signed installation with the same application ID. Debug APKs use the separate `.debug` application ID.

The release workflow reserves the first 19 version codes from the inherited baseline and adds its monotonically increasing release run number. The first fork release is 1.7.0, version code 20.
