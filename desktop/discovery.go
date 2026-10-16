package main

import (
	"encoding/json"
	"net"
	"time"
)

const discoveryPort = 47474

type probe struct {
	Flick int    `json:"flick"`
	Type  string `json:"type"`
	ID    string `json:"id"`
	Name  string `json:"name"`
}

// runDiscovery answers "who's there?" probes from phones on the Wi-Fi.
// It never returns; if the port is busy it keeps retrying.
func runDiscovery(s *Server) {
	for {
		conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: discoveryPort})
		if err != nil {
			logf("discovery: %v (retrying)", err)
			time.Sleep(5 * time.Second)
			continue
		}
		logf("discovery listening on %d", discoveryPort)
		buf := make([]byte, 4096)
		for {
			n, addr, err := conn.ReadFromUDP(buf)
			if err != nil {
				logf("discovery read: %v", err)
				break
			}
			var p probe
			if json.Unmarshal(buf[:n], &p) != nil || p.Flick != 1 || p.Type != "probe" {
				continue
			}
			id := s.Identity()
			if p.ID == id.ID {
				continue
			}
			id.Type = "here"
			b, _ := json.Marshal(id)
			if _, err := conn.WriteToUDP(b, addr); err != nil {
				logf("discovery reply to %v: %v", addr, err)
			}
		}
		conn.Close()
		time.Sleep(time.Second)
	}
}

// announce sends one broadcast so that, on a Mac, the "allow local network"
// permission prompt appears right after installation rather than on first use.
func announce(s *Server) {
	c, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4bcast, Port: discoveryPort})
	if err != nil {
		return
	}
	defer c.Close()
	b, _ := json.Marshal(map[string]any{"flick": 1, "type": "hello", "id": s.cfg.Get().ID})
	_, _ = c.Write(b)
}
