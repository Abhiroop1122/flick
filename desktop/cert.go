package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

// Each computer has its own self-signed certificate. Phones pin its SHA-256
// fingerprint (announced during discovery), so transfers are encrypted and
// nobody else on the Wi-Fi can read or tamper with them.
func loadOrCreateCert(dir, id string) (tls.Certificate, string, error) {
	crtFile := filepath.Join(dir, "flick-tls.crt")
	keyFile := filepath.Join(dir, "flick-tls.key")
	if c, err := tls.LoadX509KeyPair(crtFile, keyFile); err == nil && len(c.Certificate) > 0 {
		return c, fingerprint(c.Certificate[0]), nil
	}
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "flick-" + id, Organization: []string{"Flick"}},
		NotBefore:             now.Add(-24 * time.Hour),
		NotAfter:              now.AddDate(20, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	keyDer, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	crtPem := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPem := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDer})
	_ = os.WriteFile(keyFile, keyPem, 0o600)
	_ = os.WriteFile(crtFile, crtPem, 0o644)
	c, err := tls.X509KeyPair(crtPem, keyPem)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	return c, fingerprint(der), nil
}

func fingerprint(der []byte) string {
	h := sha256.Sum256(der)
	return hex.EncodeToString(h[:])
}
